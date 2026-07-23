package vip.mate.tool.guard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.tool.guard.engine.ToolGuardEngine;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.Set;

/**
 * 工具安全服务门面
 * <p>
 * Node 层直接调用的入口。整合全局开关 / denied 名单检查 + engine 评估 + 审计记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolGuardService {

    private final ToolGuardEngine engine;
    private final ToolGuardAuditService auditService;
    private final ToolGuardConfigService configService;

    /**
     * 评估工具调用（完整版）
     * <p>
     * 优先检查全局开关和 denied 名单（来自 ToolGuardConfigService），
     * 通过后再委托 ToolGuardEngine 做 Guardian 规则评估。
     */
    public GuardEvaluation evaluate(ToolInvocationContext context) {
        return evaluate(context, false);
    }

    /**
     * 评估工具调用，可选延迟 NEEDS_APPROVAL 行的审计记录。
     * <p>
     * {@code deferApprovalAudit=true} 时，NEEDS_APPROVAL 结果不在此处落审计——
     * 调用方在 auto-grant 决策完成后通过
     * {@link #recordApprovalAudit(ToolInvocationContext, GuardEvaluation, String, String)}
     * 补记一行，行内带上决策结果码与 pendingId。ALLOW / BLOCK 行为不变。
     */
    public GuardEvaluation evaluate(ToolInvocationContext context, boolean deferApprovalAudit) {
        // 全局开关：guard 禁用时直接放行
        if (!configService.isEnabled()) {
            return GuardEvaluation.allow(context.toolName());
        }

        // 黑名单工具：直接拦截
        Set<String> denied = configService.getDeniedTools();
        if (!denied.isEmpty() && denied.contains(context.toolName())) {
            log.info("[ToolGuardService] Tool '{}' is in denied list, blocking", context.toolName());
            return new GuardEvaluation(context.toolName(), List.of(), null,
                    GuardDecision.BLOCK, "工具 " + context.toolName() + " 已被安全策略禁用");
        }

        GuardEvaluation evaluation = engine.evaluate(context);

        // 异步审计记录（NEEDS_APPROVAL 且调用方要求延迟时跳过，由调用方补记）
        if (!(deferApprovalAudit && evaluation.shouldRequireApproval())) {
            try {
                auditService.record(context, evaluation, null);
            } catch (Exception e) {
                log.warn("[ToolGuardService] Failed to record audit: {}", e.getMessage());
            }
        }

        return evaluation;
    }

    /**
     * 补记被 {@code evaluate(context, true)} 延迟的 NEEDS_APPROVAL 审计行。
     *
     * @param autoApproveOutcome auto-grant 决策结果码（AUTO_GRANT / HARD_BLOCK /
     *                           FORCE_HUMAN:xxx / SEVERITY_CRITICAL / SEVERITY_CEILING:xxx /
     *                           UNKNOWN_WORKSPACE / NO_GRANT），未接线时为 null
     * @param pendingId          人审路径创建的待批 id，无则为 null
     */
    public void recordApprovalAudit(ToolInvocationContext context, GuardEvaluation evaluation,
                                    String pendingId, String autoApproveOutcome) {
        try {
            auditService.record(context, evaluation, pendingId, autoApproveOutcome);
        } catch (Exception e) {
            log.warn("[ToolGuardService] Failed to record approval audit: {}", e.getMessage());
        }
    }

    /**
     * 便捷评估方法
     */
    public GuardEvaluation evaluateToolCall(String toolName, String arguments,
                                             String conversationId, String agentId) {
        ToolInvocationContext context = ToolInvocationContext.of(toolName, arguments, conversationId, agentId);
        return evaluate(context);
    }

    /**
     * 评估并记录关联的 pendingId
     */
    public GuardEvaluation evaluateWithPendingId(ToolInvocationContext context, String pendingId) {
        GuardEvaluation evaluation = engine.evaluate(context);

        try {
            auditService.record(context, evaluation, pendingId);
        } catch (Exception e) {
            log.warn("[ToolGuardService] Failed to record audit: {}", e.getMessage());
        }

        return evaluation;
    }
}
