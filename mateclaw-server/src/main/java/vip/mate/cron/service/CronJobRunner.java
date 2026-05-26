package vip.mate.cron.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.cron.CronChatOriginFactory;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.wiki.service.WikiProcessingService;

/**
 * RFC-063r §2.7.1: scheduler-facing orchestrator that decomposes one cron
 * tick into the three transactional segments owned by
 * {@link CronJobLifecycleService}, with the long-running LLM call sitting
 * <em>outside</em> any transaction.
 *
 * <p><b>This class must NOT be annotated {@code @Transactional}</b> — see
 * RFC-063r §5.2:
 * <ul>
 *   <li>The class is the entry point invoked by the
 *       {@code ThreadPoolTaskScheduler}'s lambda; the LLM HTTP call inside
 *       {@link #runAgent} is seconds-to-minutes long and must not hold a DB
 *       connection.</li>
 *   <li>Self-invocation in the legacy {@code CronJobService} silently
 *       skipped {@code @Transactional}; that pattern is forbidden here.</li>
 *   <li>An {@code ArchUnit} test (see PR-3) pins this rule so a future
 *       regression fails CI.</li>
 * </ul>
 *
 * <p>The three transactional segments live on
 * {@link CronJobLifecycleService} (a separate bean), so cross-bean calls
 * route through Spring AOP and {@code REQUIRES_NEW} works as advertised.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronJobRunner {

    private final CronJobLifecycleService lifecycle;
    private final AgentService agentService;
    private final CronChatOriginFactory originFactory;
    private final vip.mate.cron.CronConversationResolver conversationResolver;
    private final WikiProcessingService wikiProcessingService;
    private final ObjectMapper objectMapper;

    /**
     * Sentinel a scheduled-job run returns when it determines there is
     * nothing to do or report. {@link #buildCronPrompt} instructs the model
     * to reply with exactly this string; {@code executeJob} then finishes the
     * run without delivering anything (an explicit, per-run no-op decision —
     * distinct from the static per-job {@code suppressAgentReply} flag).
     */
    static final String CRON_SILENT_MARKER = "[SILENT]";

    /**
     * Scheduler-facing entry. Runs three logical segments:
     * <ol>
     *   <li>T1 — {@link CronJobLifecycleService#startRun} commits run row +
     *       user message.</li>
     *   <li>Untransacted — {@link #runAgent} performs the LLM call.</li>
     *   <li>T2 — {@link CronJobLifecycleService#finishRunAndPublish} commits
     *       assistant message and publishes the completion / delivery
     *       events. {@code AFTER_COMMIT} listeners fire from a fresh DB
     *       connection, so {@link CronJobRunEntity}'s persisted state is
     *       always visible by the time delivery resolves a strategy.</li>
     * </ol>
     */
    public void executeJob(CronJobEntity job) {
        executeJob(job, /* triggerType */ "scheduled");
    }

    /** Variant with explicit trigger type — used by {@code runNow} (manual). */
    public void executeJob(CronJobEntity job, String triggerType) {
        if (job == null) {
            log.warn("[CronRunner] executeJob called with null job — ignoring");
            return;
        }

        // task_type='wiki_process' — system task with no conversation /
        // channel delivery. Parse the wiki-process payload from request_body,
        // queue the KB's raw materials for asynchronous processing, and write
        // a standalone run record.
        if ("wiki_process".equals(job.getTaskType())) {
            executeWikiProcess(job, triggerType);
            return;
        }

        String userMessage = "agent".equals(job.getTaskType())
                ? job.getRequestBody()
                : job.getTriggerMessage();

        // Resolve once and pass through the lifecycle. CronConversationResolver
        // routes Web-origin jobs to tasks_<wsId> (single visible conversation
        // per workspace) and IM-bound jobs to the channel session conversation
        // when one already exists, so cron output appears where the user
        // naturally looks rather than in an orphan cron_<id> row.
        String conversationId = conversationResolver.resolve(job);

        // T1 — short tx
        CronJobRunEntity run;
        try {
            run = lifecycle.startRun(job, userMessage, triggerType, conversationId);
        } catch (Exception e) {
            log.error("[CronRunner] T1 startRun failed for job {}: {}", job.getId(), e.getMessage(), e);
            return;
        }

        // task_type='reminder' — pure notification, no LLM call. The user
        // (or the create_reminder tool on their behalf) supplied the exact
        // text they want pushed; running it through chat() only echoes /
        // rephrases it ("收到提醒，请立即前往…"). Hand the trigger_message
        // straight to T2 so the recipient sees the literal reminder.
        // 'text' jobs still go through the LLM path below (they may need
        // tool use, e.g. weather lookup, news search).
        if ("reminder".equals(job.getTaskType())
                && job.getTriggerMessage() != null
                && !job.getTriggerMessage().isBlank()) {
            try {
                AssistantMessage direct = new AssistantMessage(job.getTriggerMessage());
                lifecycle.finishRunAndPublish(job, run, userMessage, direct, conversationId, false);
            } catch (Exception e) {
                log.error("[CronRunner] reminder direct-push failed for job {}: {}",
                        job.getId(), e.getMessage(), e);
                try {
                    lifecycle.markRunFailed(run, e);
                } catch (Exception markErr) {
                    log.warn("[CronRunner] markRunFailed after reminder failure also failed for run {}: {}",
                            run.getId(), markErr.getMessage());
                }
            }
            return;
        }

        // No-tx segment — long LLM call. RFC §5.2 hard rule: must not hold
        // any DB connection during this call.
        AgentService.ChatResult chatResult;
        AssistantMessage result;
        try {
            ChatOrigin origin = originFactory.from(job, conversationId);
            chatResult = runAgent(job, userMessage, origin, conversationId);
            result = new AssistantMessage(chatResult.content());
        } catch (Exception e) {
            log.error("[CronRunner] runAgent failed for job {}: {}", job.getId(), e.getMessage(), e);
            try {
                lifecycle.markRunFailed(run, e);
            } catch (Exception markErr) {
                // CronRunStaleCleanup will sweep status='running' rows older than 30 min.
                log.warn("[CronRunner] markRunFailed itself failed for run {}: {} (stale-cleanup will recover)",
                        run.getId(), markErr.getMessage());
            }
            return;
        }

        // Explicit no-op: the agent answered with the silent sentinel,
        // meaning there is nothing to deliver or report for this run.
        boolean silent = result.getText() != null
                && CRON_SILENT_MARKER.equals(result.getText().trim());

        // T2 — short tx
        try {
            lifecycle.finishRunAndPublish(job, run, userMessage, result, conversationId, silent, chatResult);
        } catch (Exception e) {
            log.error("[CronRunner] T2 finishRunAndPublish failed for job {}: {}", job.getId(), e.getMessage(), e);
            try {
                lifecycle.markRunFailed(run, e);
            } catch (Exception markErr) {
                log.warn("[CronRunner] markRunFailed after T2 failure also failed for run {}: {}",
                        run.getId(), markErr.getMessage());
            }
        }
    }

    /**
     * Execute a {@code wiki_process} job: parse the KB id (+ force flag) from
     * {@link CronJobEntity#getRequestBody()}, queue the KB's raw materials,
     * and record a standalone run row. No conversation, no LLM call, no
     * channel delivery — every other cron task type goes through the agent
     * path, but this one talks directly to the wiki processing service.
     */
    private void executeWikiProcess(CronJobEntity job, String triggerType) {
        CronJobRunEntity run;
        try {
            run = lifecycle.startSystemRun(job, triggerType);
        } catch (Exception e) {
            log.error("[CronRunner] startSystemRun failed for wiki_process job {}: {}",
                    job.getId(), e.getMessage(), e);
            return;
        }

        Long kbId;
        boolean force;
        try {
            JsonNode payload = parsePayload(job.getRequestBody());
            kbId = readKbId(payload);
            force = payload != null && payload.hasNonNull("force") && payload.get("force").asBoolean(false);
        } catch (Exception e) {
            log.error("[CronRunner] wiki_process payload parse failed for job {}: {}",
                    job.getId(), e.getMessage());
            try {
                lifecycle.markRunFailed(run, e);
            } catch (Exception markErr) {
                log.warn("[CronRunner] markRunFailed after payload-parse failure also failed for run {}: {}",
                        run.getId(), markErr.getMessage());
            }
            return;
        }

        try {
            int queued = wikiProcessingService.processKB(kbId, force);
            String description = "queued " + queued + " raw material(s)" + (force ? " (force)" : "");
            lifecycle.markRunSucceeded(run, description);
        } catch (Exception e) {
            log.error("[CronRunner] wiki_process job {} failed for kbId={}: {}",
                    job.getId(), kbId, e.getMessage(), e);
            try {
                lifecycle.markRunFailed(run, e);
            } catch (Exception markErr) {
                log.warn("[CronRunner] markRunFailed after wiki_process failure also failed for run {}: {}",
                        run.getId(), markErr.getMessage());
            }
        }
    }

    private JsonNode parsePayload(String requestBody) throws Exception {
        if (requestBody == null || requestBody.isBlank()) {
            throw new IllegalArgumentException("wiki_process requires a non-empty request_body");
        }
        return objectMapper.readTree(requestBody);
    }

    /**
     * Read {@code kbId} from a wiki_process payload, accepting either a JSON
     * number or a JSON string (mirrors the Snowflake precision contract:
     * frontend may send IDs as strings to avoid Number truncation).
     */
    private Long readKbId(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("kbId")) {
            throw new IllegalArgumentException("wiki_process payload missing kbId");
        }
        JsonNode v = payload.get("kbId");
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual()) {
            String text = v.asText().trim();
            if (!text.isEmpty()) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    // fall through to exception below
                }
            }
        }
        throw new IllegalArgumentException("wiki_process payload has unparseable kbId: " + v);
    }

    /**
     * Runs the agent with the scheduled-job {@link ChatOrigin} and the
     * execution-context prompt assembled by {@link #buildCronPrompt}.
     */
    private AgentService.ChatResult runAgent(CronJobEntity job, String userMessage, ChatOrigin origin,
                                              String conversationId) {
        String prompt = buildCronPrompt(userMessage, origin);
        // execute() and chat() both ultimately route through the agent's
        // StateGraph; chatWithUsage captures token + runtime model attribution
        // for either path. Plan-Execute agents stream via the same
        // chatStructuredStream the helper consumes.
        return agentService.chatWithUsage(job.getAgentId(), prompt, conversationId, origin);
    }

    /**
     * Assemble the prompt for a scheduled-job run. An execution-context note
     * is always prepended so the model behaves as a scheduled task rather
     * than as a reply to a live user message:
     * <ul>
     *   <li>the task is self-contained and runs in isolation — no prior
     *       conversation history is in scope, so the model must not assume
     *       earlier context;</li>
     *   <li>(channel-bound runs only) delivery back to the originating
     *       channel is framework-handled, so the model must not invent
     *       CLI / shell / "send to WeChat" tool calls to deliver the result;</li>
     *   <li>when there is genuinely nothing to do or report, the model
     *       should reply with exactly {@link #CRON_SILENT_MARKER} and nothing
     *       else, which suppresses delivery for this run.</li>
     * </ul>
     */
    static String buildCronPrompt(String userMessage, ChatOrigin origin) {
        String body = userMessage != null ? userMessage : "";
        boolean channelBound = origin != null && origin.channelId() != null;
        StringBuilder sb = new StringBuilder();
        sb.append("[定时任务执行说明]\n");
        sb.append("本次对话由定时任务自动触发，不是用户实时发来的消息。\n");
        sb.append("- 请把下面的「任务指令」当作一个完整、独立的任务来执行；")
          .append("本次为隔离执行，没有此前的对话历史，不要假设存在上下文。\n");
        if (channelBound) {
            sb.append("- 执行结果会由系统自动投递回原渠道，你只需直接给出最终结果内容，")
              .append("不要尝试调用 CLI / shell / \"发送到微信\"等工具自行投递。\n");
        }
        sb.append("- 如果确认本次确实无需执行、也没有新内容可汇报，")
          .append("请仅回复 \"").append(CRON_SILENT_MARKER).append("\"，不要附加任何其它文字。\n\n");
        sb.append("[任务指令]\n").append(body);
        return sb.toString();
    }
}
