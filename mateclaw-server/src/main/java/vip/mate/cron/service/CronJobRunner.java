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
                lifecycle.finishRunAndPublish(job, run, userMessage, direct, conversationId);
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
        AssistantMessage result;
        try {
            ChatOrigin origin = originFactory.from(job, conversationId);
            result = runAgent(job, userMessage, origin, conversationId);
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

        // T2 — short tx
        try {
            lifecycle.finishRunAndPublish(job, run, userMessage, result, conversationId);
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
     * Runs the agent with the cron-derived {@link ChatOrigin} and the
     * RFC-063r §2.13 system-prompt guard prepended when the cron is bound to
     * a channel — fixes the Issue #25 LLM hallucination ("install
     * mateclaw cli to send to wechat") by telling the model that delivery is
     * framework-handled.
     */
    private AssistantMessage runAgent(CronJobEntity job, String userMessage, ChatOrigin origin,
                                      String conversationId) {
        String guarded = wrapWithDeliveryGuard(userMessage, origin);
        String text = "agent".equals(job.getTaskType())
                ? agentService.execute(job.getAgentId(), guarded, conversationId, origin)
                : agentService.chat(job.getAgentId(), guarded, conversationId, origin);
        return new AssistantMessage(text != null ? text : "");
    }

    /**
     * RFC-063r §2.13: when the cron is bound to a channel, prepend an
     * explicit system note telling the LLM that delivery is handled by the
     * framework. Without this, the model invents tools ("call CLI to send
     * to wechat") and surfaces "command not found" style errors to users
     * (Issue #25 second symptom).
     *
     * <p>Web-origin crons (no channelId) bypass the wrapper so the
     * pre-RFC behavior is preserved.
     */
    static String wrapWithDeliveryGuard(String userMessage, ChatOrigin origin) {
        String body = userMessage != null ? userMessage : "";
        if (origin == null || origin.channelId() == null) {
            return body;
        }
        return """
                [系统说明]
                本次执行由定时任务触发，结果将由系统自动投递回原渠道，
                你只需直接给出最终回复内容，不要尝试调用 CLI / shell /
                "发送到微信"等工具——这些操作由框架完成。

                [用户原始消息]
                """ + body;
    }
}
