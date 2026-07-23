package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.tool.guard.model.*;
import vip.mate.tool.guard.repository.ToolGuardAuditLogMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具安全审计服务
 * <p>
 * 支持审计开关、最低记录等级过滤、过期日志自动清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolGuardAuditService {

    private final ToolGuardAuditLogMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final ToolGuardConfigService configService;

    /**
     * 异步记录审计日志
     * <p>
     * 受审计开关和最低等级过滤控制：
     * <ul>
     *   <li>auditEnabled=false → 不记录</li>
     *   <li>maxSeverity 低于 auditMinSeverity → 不记录</li>
     * </ul>
     */
    @Async
    public void record(ToolInvocationContext context, GuardEvaluation evaluation, String pendingId) {
        record(context, evaluation, pendingId, null);
    }

    /**
     * 记录审计日志并附带自动批准决策结果。
     * <p>
     * {@code autoApproveOutcome} 为 NEEDS_APPROVAL 调用经过 auto-grant 决策层后的
     * 结果码（AUTO_GRANT / SEVERITY_CEILING:… / NO_GRANT 等），未经过该层时为 null。
     */
    @Async
    public void record(ToolInvocationContext context, GuardEvaluation evaluation,
                       String pendingId, String autoApproveOutcome) {
        try {
            // 审计开关检查
            if (!configService.isAuditEnabled()) {
                return;
            }

            // 等级过滤：无 finding 时 maxSeverity 为 null，视为 INFO 级
            GuardSeverity minSeverity = configService.getAuditMinSeverity();
            GuardSeverity actualSeverity = evaluation.maxSeverity() != null
                    ? evaluation.maxSeverity() : GuardSeverity.INFO;
            if (!actualSeverity.isAtLeast(minSeverity)) {
                return;
            }

            ToolGuardAuditLogEntity entity = new ToolGuardAuditLogEntity();
            entity.setConversationId(context.conversationId());
            entity.setAgentId(context.agentId());
            entity.setUserId(context.userId());
            entity.setChannelType(context.channelType());
            entity.setToolName(context.toolName());
            entity.setToolParamsJson(truncate(context.rawArguments(), 2000));
            entity.setDecision(evaluation.decision().name());
            entity.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
            entity.setPendingId(pendingId);
            entity.setAutoApproveOutcome(autoApproveOutcome);

            if (evaluation.hasFindings()) {
                entity.setFindingsJson(serializeFindings(evaluation));
            }

            auditMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[ToolGuardAudit] Failed to record audit log: {}", e.getMessage());
        }
    }

    /**
     * 分页查询审计日志
     */
    public IPage<ToolGuardAuditLogEntity> listAll(int page, int size,
                                                    String toolName, String decision,
                                                    String conversationId) {
        LambdaQueryWrapper<ToolGuardAuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        if (toolName != null && !toolName.isBlank()) {
            wrapper.eq(ToolGuardAuditLogEntity::getToolName, toolName);
        }
        if (decision != null && !decision.isBlank()) {
            wrapper.eq(ToolGuardAuditLogEntity::getDecision, decision);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            wrapper.eq(ToolGuardAuditLogEntity::getConversationId, conversationId);
        }
        wrapper.orderByDesc(ToolGuardAuditLogEntity::getCreateTime);
        return auditMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 按会话查询审计日志
     */
    public IPage<ToolGuardAuditLogEntity> listByConversation(String conversationId, int page, int size) {
        return listAll(page, size, null, null, conversationId);
    }

    /**
     * 审计统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", auditMapper.selectCount(null));
        stats.put("blocked", auditMapper.selectCount(
                new LambdaQueryWrapper<ToolGuardAuditLogEntity>()
                        .eq(ToolGuardAuditLogEntity::getDecision, "BLOCK")));
        stats.put("needsApproval", auditMapper.selectCount(
                new LambdaQueryWrapper<ToolGuardAuditLogEntity>()
                        .eq(ToolGuardAuditLogEntity::getDecision, "NEEDS_APPROVAL")));
        stats.put("allowed", auditMapper.selectCount(
                new LambdaQueryWrapper<ToolGuardAuditLogEntity>()
                        .eq(ToolGuardAuditLogEntity::getDecision, "ALLOW")));
        return stats;
    }

    /**
     * 定时清理过期审计日志（每天凌晨 3 点）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredAuditLogs() {
        try {
            int days = configService.getAuditRetentionDays();
            if (days <= 0) return;

            LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
            int deleted = auditMapper.delete(
                    new LambdaQueryWrapper<ToolGuardAuditLogEntity>()
                            .lt(ToolGuardAuditLogEntity::getCreateTime, cutoff));
            if (deleted > 0) {
                log.info("[ToolGuardAudit] Cleaned {} expired records (older than {} days)", deleted, days);
            }
        } catch (Exception e) {
            log.warn("[ToolGuardAudit] Failed to clean expired logs: {}", e.getMessage());
        }
    }

    private String serializeFindings(GuardEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation.findingsToMapList());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
