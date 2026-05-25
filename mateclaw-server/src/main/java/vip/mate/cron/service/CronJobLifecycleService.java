package vip.mate.cron.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.cron.delivery.CronJobCompletedEvent;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.i18n.I18nService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;

import java.time.LocalDateTime;

/**
 * RFC-063r §2.7.2: three-segment transactional support for {@link CronJobRunner}.
 *
 * <p>Each method runs in its own short {@code REQUIRES_NEW} transaction so
 * the long LLM call between T1 and T2 never holds a DB connection.
 *
 * <ul>
 *   <li>{@code T1} — {@link #startRun}: insert run row + persist user message.</li>
 *   <li>{@code T-fail} — {@link #markRunFailed}: terminal state when the LLM
 *       call throws.</li>
 *   <li>{@code T2} — {@link #finishRunAndPublish}: persist assistant
 *       message + dispatch the conversation-completed event (memory pipeline)
 *       + the cron-job-completed event (delivery pipeline).</li>
 * </ul>
 *
 * <p>Lives in a separate {@code @Service} bean so cross-bean invocation from
 * {@link CronJobRunner} routes through the Spring AOP proxy (RFC §5.2 hard
 * rule). The lifecycle service is deliberately the only place
 * {@code @Transactional} appears in the cron-execution path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronJobLifecycleService {

    private final CronJobRunMapper runMapper;
    private final ConversationService conversationService;
    private final ConversationCompletionPublisher completionPublisher;
    private final ApplicationEventPublisher events;
    private final I18nService i18n;

    /**
     * T1 — short transaction: persist a run row in {@code running} state,
     * persist the user message that triggered the run, and commit. Returns
     * the persisted entity so callers can observe its assigned id without a
     * second SELECT.
     *
     * @param triggerType {@code scheduled} (cron tick) or {@code manual} (runNow)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CronJobRunEntity startRun(CronJobEntity job, String userMessage, String triggerType,
                                     String conversationId) {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setCronJobId(job.getId());
        run.setConversationId(conversationId);
        run.setStatus("running");
        run.setTriggerType(triggerType != null ? triggerType : "scheduled");
        run.setStartedAt(LocalDateTime.now());
        run.setDeliveryStatus("NONE");
        runMapper.insert(run);

        // Self-heal the parent conversation row before saving messages.
        // saveMessage only inserts message rows; if the conversation row is
        // missing (e.g. tasks_<wsId> seed never ran on this DB, or was
        // deleted manually) the messages become orphans the sidebar cannot
        // surface. getOrCreateSharedConversation makes the row land with
        // username=system so every workspace member sees it.
        conversationService.getOrCreateSharedConversation(
                conversationId, job.getAgentId(), job.getWorkspaceId());

        // Cron-run header (system role) so users browsing the unified
        // tasks_<wsId> conversation can tell which job's run starts here.
        // Renderable as a divider card on the frontend; LLM history reads
        // skip system messages so this doesn't pollute future prompts.
        conversationService.saveMessage(conversationId, "system",
                buildHeader(job, run));

        // Persist the user message before the LLM call so history reads
        // see a coherent (user → assistant) ordering even if the agent
        // throws mid-run.
        if (userMessage != null && !userMessage.isBlank()) {
            conversationService.saveMessage(conversationId, "user", userMessage);
        }
        return run;
    }

    /**
     * Format a cron-run header row. Pattern is parsed by the frontend
     * MessageBubble which renders it as a labeled divider when role=system.
     * Format: "📋 [{jobName}] {triggerType} · {timestamp}"
     */
    private String buildHeader(CronJobEntity job, CronJobRunEntity run) {
        String triggerKey = "manual".equalsIgnoreCase(run.getTriggerType())
                ? "cron.run_header.manual" : "cron.run_header.scheduled";
        String triggerLabel = i18n != null ? i18n.msg(triggerKey)
                : ("manual".equalsIgnoreCase(run.getTriggerType()) ? "manual" : "scheduled");
        String fallbackTitle = i18n != null ? i18n.msg("cron.tasks_conversation.title") : "Scheduled Tasks";
        return String.format("📋 %s · %s · %s",
                job.getName() != null ? job.getName() : fallbackTitle,
                triggerLabel,
                run.getStartedAt());
    }

    /**
     * T-fail — short transaction: flag the run row as failed when the agent
     * throws. Always-best-effort policy: delivery_status stays NONE; nothing
     * is published.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunFailed(CronJobRunEntity run, Throwable error) {
        String message = error != null && error.getMessage() != null ? error.getMessage() : "unknown error";
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getStatus, "failed")
                .set(CronJobRunEntity::getFinishedAt, LocalDateTime.now())
                .set(CronJobRunEntity::getErrorMessage, StrUtil.maxLength(message, 1000)));
    }

    /**
     * Insert a {@code running} run row for a task type that does not produce
     * a conversation (e.g. {@code wiki_process}). No header / user message is
     * saved and no conversation row is touched, because there is no recipient
     * to surface the run to.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CronJobRunEntity startSystemRun(CronJobEntity job, String triggerType) {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setCronJobId(job.getId());
        run.setConversationId(null);
        run.setStatus("running");
        run.setTriggerType(triggerType != null ? triggerType : "scheduled");
        run.setStartedAt(LocalDateTime.now());
        run.setDeliveryStatus("NONE");
        runMapper.insert(run);
        return run;
    }

    /**
     * Mark a {@link #startSystemRun system run} as succeeded with a short
     * description of what was done (e.g. "queued 5 raw materials"). The
     * description lands in {@code error_message} so the dashboard's existing
     * "last run" line surfaces it without a schema change.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunSucceeded(CronJobRunEntity run, String description) {
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getStatus, "succeeded")
                .set(CronJobRunEntity::getFinishedAt, LocalDateTime.now())
                .set(CronJobRunEntity::getErrorMessage,
                        description != null ? StrUtil.maxLength(description, 1000) : null));
    }

    /**
     * T2 — short transaction: persist the assistant reply, mark the run
     * succeeded, then publish the two domain events. The
     * {@code @TransactionalEventListener(AFTER_COMMIT)} listeners only run
     * once this method's tx commits, so cross-connection reads in the
     * delivery / memory pipelines always see the final state.
     *
     * @param silent {@code true} when the agent returned the no-op sentinel —
     *               the run succeeded but produced nothing to report. A short
     *               marker message is persisted for conversation coherence,
     *               and both the delivery and memory pipelines are skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRunAndPublish(CronJobEntity job, CronJobRunEntity run,
                                    String userMessage, AssistantMessage result,
                                    String conversationId, boolean silent) {
        finishRunAndPublish(job, run, userMessage, result, conversationId, silent, null);
    }

    /**
     * @param chatResult optional usage attribution from the LLM path; pass
     *                   {@code null} for non-LLM paths (e.g. reminder
     *                   direct-push) so the assistant row is persisted with
     *                   zero token counts and null runtime model attribution.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRunAndPublish(CronJobEntity job, CronJobRunEntity run,
                                    String userMessage, AssistantMessage result,
                                    String conversationId, boolean silent,
                                    AgentService.ChatResult chatResult) {
        String convId = conversationId != null ? conversationId : run.getConversationId();
        String text = result != null && result.getText() != null ? result.getText() : "";

        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getStatus, "succeeded")
                .set(CronJobRunEntity::getFinishedAt, LocalDateTime.now()));

        if (silent) {
            // No-op run: persist a short marker so the tasks_<wsId>
            // conversation keeps a coherent user -> assistant pairing, then
            // return without delivery or memory extraction — there is no
            // real content to deliver or to learn from.
            String marker = i18n != null ? i18n.msg("cron.run.silent")
                    : "（本次定时任务无新内容，已跳过）";
            conversationService.saveMessage(convId, "assistant", marker);
            return;
        }

        if (chatResult != null) {
            conversationService.saveMessage(convId, "assistant", text, null, "completed",
                    chatResult.promptTokens(), chatResult.completionTokens(),
                    chatResult.runtimeModel(), chatResult.runtimeProvider());
        } else {
            conversationService.saveMessage(convId, "assistant", text);
        }

        // Memory pipeline (existing behavior preserved — was inline in the
        // old executeJob; now lives behind the same publisher used by the
        // web / channel paths so cron is no longer special-cased).
        try {
            completionPublisher.publish(job.getAgentId(), convId, userMessage, text, "cron");
        } catch (Exception e) {
            // Memory failures must not break delivery — log + carry on.
            log.warn("[CronLifecycle] completionPublisher failed for job {}: {}", job.getId(), e.getMessage());
        }

        // Delivery pipeline (RFC-063r §2.7.3) — fired here so listeners only
        // run after T2 commits.
        events.publishEvent(new CronJobCompletedEvent(job, result != null ? result : new AssistantMessage(""), run));
    }
}
