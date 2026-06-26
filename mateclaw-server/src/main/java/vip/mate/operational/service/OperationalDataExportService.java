package vip.mate.operational.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.audit.model.AuditEventEntity;
import vip.mate.audit.repository.AuditEventMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.repository.CronJobMapper;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.dashboard.service.DashboardService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelConfigMapper;
import vip.mate.llm.repository.ModelProviderMapper;
import vip.mate.operational.model.ExportTask;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.repository.SkillUsageStatMapper;
import vip.mate.skill.usage.SkillUsageStatEntity;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardAuditLogMapper;
import vip.mate.tool.guard.repository.ToolGuardConfigMapper;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;
import vip.mate.workspace.conversation.TokenUsageService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.vo.TokenUsageSummaryVO;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Operational data export service: asynchronously builds a 9-sheet Excel report
 * and serves it through a one-time download.
 * <p>
 * Data-sourcing strategy: prefer reusing existing service methods, fall back to
 * direct mapper queries, then aggregate in memory.
 */
@Service
public class OperationalDataExportService {

    private static final Logger log = LoggerFactory.getLogger(OperationalDataExportService.class);

    // ── 现成 Service ──
    private final TokenUsageService tokenUsageService;
    private final DashboardService dashboardService;
    private final AuditEventService auditEventService;

    // ── Mapper（按需注入）──
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final SkillMapper skillMapper;
    private final SkillUsageStatMapper skillUsageStatMapper;
    private final AgentSkillBindingMapper agentSkillBindingMapper;
    private final AgentMapper agentMapper;
    private final ChannelMapper channelMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final ModelProviderMapper modelProviderMapper;
    private final AuditEventMapper auditEventMapper;
    private final ToolGuardRuleMapper toolGuardRuleMapper;
    private final ToolGuardConfigMapper toolGuardConfigMapper;
    private final ToolGuardAuditLogMapper toolGuardAuditLogMapper;
    private final ToolApprovalMapper toolApprovalMapper;
    private final ApprovalGrantMapper approvalGrantMapper;
    private final CronJobMapper cronJobMapper;
    private final CronJobRunMapper cronJobRunMapper;

    // ── 并发控制 ──
    private final ObjectMapper objectMapper;
    private final AtomicBoolean generating = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ExportTask> tasks = new ConcurrentHashMap<>();

    public OperationalDataExportService(TokenUsageService tokenUsageService,
                                        DashboardService dashboardService,
                                        AuditEventService auditEventService,
                                        ObjectMapper objectMapper,
                                        MessageMapper messageMapper,
                                        ConversationMapper conversationMapper,
                                        UserMapper userMapper,
                                        WorkspaceMapper workspaceMapper,
                                        SkillMapper skillMapper,
                                        SkillUsageStatMapper skillUsageStatMapper,
                                        AgentSkillBindingMapper agentSkillBindingMapper,
                                        AgentMapper agentMapper,
                                        ChannelMapper channelMapper,
                                        ModelConfigMapper modelConfigMapper,
                                        ModelProviderMapper modelProviderMapper,
                                        AuditEventMapper auditEventMapper,
                                        ToolGuardRuleMapper toolGuardRuleMapper,
                                        ToolGuardConfigMapper toolGuardConfigMapper,
                                        ToolGuardAuditLogMapper toolGuardAuditLogMapper,
                                        ToolApprovalMapper toolApprovalMapper,
                                        ApprovalGrantMapper approvalGrantMapper,
                                        CronJobMapper cronJobMapper,
                                        CronJobRunMapper cronJobRunMapper) {
        this.tokenUsageService = tokenUsageService;
        this.dashboardService = dashboardService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.userMapper = userMapper;
        this.workspaceMapper = workspaceMapper;
        this.skillMapper = skillMapper;
        this.skillUsageStatMapper = skillUsageStatMapper;
        this.agentSkillBindingMapper = agentSkillBindingMapper;
        this.agentMapper = agentMapper;
        this.channelMapper = channelMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.modelProviderMapper = modelProviderMapper;
        this.auditEventMapper = auditEventMapper;
        this.toolGuardRuleMapper = toolGuardRuleMapper;
        this.toolGuardConfigMapper = toolGuardConfigMapper;
        this.toolGuardAuditLogMapper = toolGuardAuditLogMapper;
        this.toolApprovalMapper = toolApprovalMapper;
        this.approvalGrantMapper = approvalGrantMapper;
        this.cronJobMapper = cronJobMapper;
        this.cronJobRunMapper = cronJobRunMapper;
    }

    // ── 存储目录 ──

    // ── 常量 ──
    private static final int MAX_DAYS_FRONTEND = 90;
    private static final int LIMIT_USER_MSGS = 50_000;
    private static final int LIMIT_AUDIT = 10_000;
    private static final long DEADLINE_MS = 300_000; // 5 min
    private static final Path EXPORT_DIR = Path.of(".", "data", "exports");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── 值转译字典 ──
    private static final Map<String, String> LABEL_MAP = Map.ofEntries(
        Map.entry("TRUE", "启用"), Map.entry("1", "启用"),
        Map.entry("FALSE", "禁用"), Map.entry("0", "禁用"),
        Map.entry("builtin", "内置"), Map.entry("mcp", "MCP"),
        Map.entry("dynamic", "动态"), Map.entry("acp", "ACP"),
        Map.entry("active", "活跃"), Map.entry("stale", "待归档"), Map.entry("archived", "已归档"),
        Map.entry("CREATE", "创建"), Map.entry("UPDATE", "更新"), Map.entry("DELETE", "删除"),
        Map.entry("AGENT", "Agent"), Map.entry("SKILL", "Skill"), Map.entry("TOOL", "工具"),
        Map.entry("CHANNEL", "渠道"), Map.entry("WORKSPACE", "工作区"), Map.entry("USER", "用户"),
        Map.entry("rule", "防护规则"), Map.entry("audit", "防护审计"),
        Map.entry("approval", "工具审批"), Map.entry("grant", "自动授权"),
        Map.entry("config", "全局配置"), Map.entry("business_audit", "业务审计"),
        Map.entry("critical", "严重"), Map.entry("high", "高"),
        Map.entry("medium", "中"), Map.entry("low", "低"), Map.entry("info", "提示"),
        Map.entry("ALLOW", "允许"), Map.entry("BLOCK", "阻止"), Map.entry("NEEDS_APPROVAL", "需审批"),
        Map.entry("PENDING", "待处理"), Map.entry("APPROVED", "已批准"),
        Map.entry("DENIED", "已拒绝"), Map.entry("EXPIRED", "已过期"),
        Map.entry("revoked", "已撤销"), Map.entry("enabled", "启用"), Map.entry("disabled", "禁用"),
        Map.entry("chat", "对话"), Map.entry("embedding", "嵌入"), Map.entry("image", "图像")
    );

    // ════════════════════════════════════════════════════
    //  公开方法
    // ════════════════════════════════════════════════════

    /** 前端触发生成（≤90 天、有死线） */
    public ExportTask generate(LocalDate start, LocalDate end) {
        final LocalDate from = start.isAfter(end) ? end : start;
        final LocalDate to = start.isAfter(end) ? start : end;
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS_FRONTEND) {
            throw new IllegalArgumentException("时间跨度不能超过 " + MAX_DAYS_FRONTEND + " 天");
        }
        if (!generating.compareAndSet(false, true)) {
            throw new IllegalStateException("正在生成中，请等待");
        }
        ExportTask task = new ExportTask();
        try {
            tasks.put(task.getTaskId(), task);
            CompletableFuture.runAsync(() -> runExport(task, from, to, true));
        } catch (RuntimeException e) {
            // Async submission failed, so runExport's finally will never release
            // the lock — release it here to avoid wedging the flag permanently.
            generating.set(false);
            throw e;
        }
        return task;
    }

    /** 后台直接调用（无限制） */
    public ExportTask exportBackend(LocalDate start, LocalDate end) {
        if (!generating.compareAndSet(false, true)) {
            throw new IllegalStateException("前端或后台已有生成任务在运行");
        }
        ExportTask task = new ExportTask();
        tasks.put(task.getTaskId(), task);
        runExport(task, start, end, false);
        return task;
    }

    /**
     * Backend-only export entry point for the CLI: no 90-day cap and no timeout,
     * returns the ZIP bytes directly instead of writing to disk. Reachable only
     * from the operator CLI, never over HTTP.
     */
    public byte[] exportBackendBytes(LocalDate start, LocalDate end) {
        if (!generating.compareAndSet(false, true)) {
            throw new IllegalStateException("前端或后台已有生成任务在运行");
        }
        try {
            long t0 = System.currentTimeMillis();
            byte[] zip = doExport(start, end, step -> {}, false);
            log.info("CLI export completed: {} KB, {}ms", zip.length / 1024,
                     System.currentTimeMillis() - t0);
            try {
                auditEventService.recordSync("EXPORT", "OPERATIONAL_DATA",
                    start + "~" + end, "运营数据导出 9 sheets (CLI)", null);
            } catch (Exception e) {
                log.warn("Failed to write audit for CLI export: {}", e.getMessage());
            }
            return zip;
        } catch (Exception e) {
            log.error("CLI export failed", e);
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        } finally {
            generating.set(false);
        }
    }

    /** 查询任务进度 */
    public ExportTask getProgress(String taskId) {
        return tasks.get(taskId);
    }

    /** 下载（一次性） */
    public ExportTask confirmDownload(String taskId, String token) {
        ExportTask task = tasks.get(taskId);
        if (task == null) return null;
        if (!token.equals(task.getDownloadToken())) return null;
        if (!"completed".equals(task.getStatus())) return null;
        // Atomically claim the one-time download so concurrent requests cannot
        // both succeed; a second caller gets null (treated as 410 Gone).
        if (!task.claimDownload()) return null;
        return task;
    }

    // ════════════════════════════════════════════════════
    //  核心生成
    // ════════════════════════════════════════════════════

    private void runExport(ExportTask task, LocalDate start, LocalDate end, boolean enforceLimit) {
        long startedAt = System.currentTimeMillis();
        try {
            byte[] zip = doExport(start, end, step -> {
                task.setStep(step);
                if (enforceLimit && System.currentTimeMillis() - startedAt > DEADLINE_MS) {
                    throw new RuntimeException("生成超时");
                }
            }, enforceLimit);

            Files.createDirectories(EXPORT_DIR);
            String fileName = "ops_data_" + start + "_" + end + ".zip";
            Path file = EXPORT_DIR.resolve(fileName);
            Files.write(file, zip);
            task.setCompleted(file);
            log.info("Export completed: {}, size={}KB", task.getTaskId(), zip.length / 1024);

            try {
                auditEventService.recordSync("EXPORT", "OPERATIONAL_DATA",
                    start + "~" + end, "运营数据导出 9 sheets", null);
            } catch (Exception e) {
                log.warn("Failed to write audit for export: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Export failed: {}", task.getTaskId(), e);
            task.setFailed(e.getMessage());
        } finally {
            generating.set(false);
        }
    }

    byte[] doExport(LocalDate start, LocalDate end, Consumer<Integer> onStep,
                    boolean enforceLimit) throws IOException {
        LocalDateTime startTime = start.atStartOfDay();
        LocalDateTime endTime = end.atTime(LocalTime.MAX);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            // CellStyles
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numStyle = createNumStyle(wb);
            CellStyle dateStyle = createDateStyle(wb);

            // Sheet 1: 概览汇总
            onStep.accept(1);
            buildOverviewSheet(wb, headerStyle, startTime, endTime);

            // Sheet 2: Token用量
            onStep.accept(2);
            buildTokenSheet(wb, headerStyle, numStyle, start, end);

            // Sheet 3: 技能统计
            onStep.accept(3);
            buildSkillSheet(wb, headerStyle, numStyle, dateStyle, startTime, endTime);

            // Sheet 4: 用户统计
            onStep.accept(4);
            buildUserStatSheet(wb, headerStyle, numStyle, dateStyle, startTime, endTime);

            // Sheet 5: 用户对话
            onStep.accept(5);
            buildUserDetailSheet(wb, headerStyle, numStyle, dateStyle, startTime, endTime, enforceLimit);

            // Sheet 6: 安全与审计
            onStep.accept(6);
            buildSecuritySheet(wb, headerStyle, dateStyle, startTime, endTime, enforceLimit);

            // Sheet 7: 渠道统计
            onStep.accept(7);
            buildChannelSheet(wb, headerStyle, numStyle, startTime, endTime);

            // Sheet 8: 模型配置
            onStep.accept(8);
            buildModelSheet(wb, headerStyle, numStyle);

            // Sheet 9: 定时任务
            onStep.accept(9);
            buildCronSheet(wb, headerStyle, dateStyle, startTime, endTime);

            wb.write(bos);
            byte[] xlsx = bos.toByteArray();

            // ZIP
            ByteArrayOutputStream zbos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(zbos)) {
                ZipEntry entry = new ZipEntry("ops_data_" + start + "_" + end + ".xlsx");
                zos.putNextEntry(entry);
                zos.write(xlsx);
                zos.closeEntry();
            }
            return zbos.toByteArray();
        }
    }

    // ══════════════════════════════════════════════════
    //  Sheet 1: 概览汇总
    // ══════════════════════════════════════════════════

    private void buildOverviewSheet(XSSFWorkbook wb, CellStyle headerStyle,
                                    LocalDateTime startTime, LocalDateTime endTime) {
        Sheet sheet = wb.createSheet("概览汇总");
        sheet.createFreezePane(0, 1);
        int r = 0;

        // A: 运营指标（区间）
        Row aTitle = sheet.createRow(r++);
        createCell(aTitle, 0, "A: 运营指标（区间）", headerStyle);
        Map<String, Object> stats = queryOverviewStats(null, startTime, endTime);
        long activeUsers = conversationMapper.selectList(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getDeleted, 0)
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime)
                .select(ConversationEntity::getUsername))
            .stream().map(ConversationEntity::getUsername).filter(Objects::nonNull).distinct().count();
        addKvRow(sheet, r++, "对话数", stats.getOrDefault("conversations", 0L).toString(), headerStyle);
        addKvRow(sheet, r++, "消息数", stats.getOrDefault("messages", 0L).toString(), headerStyle);
        addKvRow(sheet, r++, "工具调用次数", stats.getOrDefault("toolCalls", 0L).toString(), headerStyle);
        long totalTokensVal = ((Number) stats.getOrDefault("totalTokens", 0L)).longValue();
        long conversationsVal = ((Number) stats.getOrDefault("conversations", 0L)).longValue();
        addKvRow(sheet, r++, "Token 消耗", String.valueOf(totalTokensVal), headerStyle);
        addKvRow(sheet, r++, "平均Token/对话", conversationsVal > 0 ? String.format("%.0f", (double) totalTokensVal / conversationsVal) : "-", headerStyle);
        addKvRow(sheet, r++, "活跃用户数", String.valueOf(activeUsers), headerStyle);
        r++;

        // B: 系统快照
        Row bTitle = sheet.createRow(r++);
        createCell(bTitle, 0, "B: 系统快照（当前）", headerStyle);
        long skillCount = skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getDeleted, 0));
        long agentTotal = agentMapper.selectCount(new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getDeleted, 0));
        long agentEnabled = agentMapper.selectCount(new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getDeleted, 0).eq(AgentEntity::getEnabled, true));
        addKvRow(sheet, r++, "技能总数", String.valueOf(skillCount), headerStyle);
        addKvRow(sheet, r++, "Agent 总数（已启用/总数）", agentEnabled + " / " + agentTotal, headerStyle);
        addKvRow(sheet, r++, "定时任务数（已启用）",
            String.valueOf(cronJobMapper.selectCount(new LambdaQueryWrapper<CronJobEntity>().eq(CronJobEntity::getEnabled, true).eq(CronJobEntity::getDeleted, 0))), headerStyle);
        r++;

        // C: 最近 7 天活跃度
        Row cTitle = sheet.createRow(r++);
        createCell(cTitle, 0, "C: 最近 7 天活跃度", headerStyle);
        // Sum all 7 days from trend
        List<Map<String, Object>> weekTrend = dashboardService.getTrend(null, 7);
        long weekConv = 0, weekMsg = 0, weekTok = 0;
        for (Map<String, Object> d : weekTrend) {
            weekConv += toLong(d.getOrDefault("conversations", 0L));
            weekMsg += toLong(d.getOrDefault("messages", 0L));
            weekTok += toLong(d.getOrDefault("totalTokens", 0L));
        }
        addKvRow(sheet, r++, "最近7天对话数", String.valueOf(weekConv), headerStyle);
        addKvRow(sheet, r++, "最近7天消息数", String.valueOf(weekMsg), headerStyle);
        addKvRow(sheet, r++, "最近7天 Token 消耗", String.valueOf(weekTok), headerStyle);
        r++;

        // D: 周期对比
        Map<String, Object> overview = dashboardService.getOverview(null);
        Map<String, Object> todayOv = (Map<String, Object>) overview.get("today");
        Map<String, Object> weekOv = (Map<String, Object>) overview.get("thisWeek");
        Map<String, Object> monthOv = (Map<String, Object>) overview.get("thisMonth");
        Row dTitle = sheet.createRow(r++);
        createCell(dTitle, 0, "D: 周期对比", headerStyle);
        createCell(dTitle, 1, "今日 / 本周 / 本月", headerStyle);
        addKvRow(sheet, r++, "对话数",
            todayOv.getOrDefault("conversations", 0) + " / " + weekOv.getOrDefault("conversations", 0) + " / " + monthOv.getOrDefault("conversations", 0) + " 次对话", headerStyle);
        addKvRow(sheet, r++, "消息数",
            todayOv.getOrDefault("messages", 0) + " / " + weekOv.getOrDefault("messages", 0) + " / " + monthOv.getOrDefault("messages", 0) + " 条消息", headerStyle);
        addKvRow(sheet, r++, "Token 消耗",
            todayOv.getOrDefault("totalTokens", 0) + " / " + weekOv.getOrDefault("totalTokens", 0) + " / " + monthOv.getOrDefault("totalTokens", 0) + " tokens", headerStyle);
        addKvRow(sheet, r++, "工具调用",
            todayOv.getOrDefault("toolCalls", 0) + " / " + weekOv.getOrDefault("toolCalls", 0) + " / " + monthOv.getOrDefault("toolCalls", 0) + " 次", headerStyle);
        r++;

        // E: 当前模型详情（仅已配置的 Provider）
        Row eTitle = sheet.createRow(r++);
        createCell(eTitle, 0, "E: 当前模型详情", headerStyle);
        List<ModelProviderEntity> allProviders = modelProviderMapper.selectList(
            new LambdaQueryWrapper<ModelProviderEntity>());
        Set<String> configuredProvIds = allProviders.stream()
            .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
            .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
            .map(ModelProviderEntity::getProviderId)
            .collect(Collectors.toSet());
        Map<String, String> provIdToName = allProviders.stream().collect(Collectors.toMap(ModelProviderEntity::getProviderId, ModelProviderEntity::getName));
        List<ModelConfigEntity> activeConfigs = modelConfigMapper.selectList(
            new LambdaQueryWrapper<ModelConfigEntity>().eq(ModelConfigEntity::getDeleted, 0));
        boolean hasAny = false;
        for (ModelConfigEntity cfg : activeConfigs) {
            if (!configuredProvIds.contains(cfg.getProvider())) continue;
            hasAny = true;
            String provName = provIdToName.getOrDefault(cfg.getProvider(), "-");
            addKvRow(sheet, r++, "Provider 名称", provName, headerStyle);
            addKvRow(sheet, r++, "模型名称", cfg.getModelName() != null ? cfg.getModelName() : "-", headerStyle);
            addKvRow(sheet, r++, "模型类型", label(cfg.getModelType() != null ? cfg.getModelType() : "-"), headerStyle);
            addKvRow(sheet, r++, "Max Tokens", cfg.getMaxTokens() != null ? String.valueOf(cfg.getMaxTokens()) : "-", headerStyle);
            addKvRow(sheet, r++, "Temperature", cfg.getTemperature() != null ? String.valueOf(cfg.getTemperature()) : "-", headerStyle);
            if (cfg.getIsDefault() != null && cfg.getIsDefault()) {
                addKvRow(sheet, r++, "默认模型", "是", headerStyle);
            }
            r++;
        }
        r++;

        // F: Agent 活跃排名 — by conversation.agentId → agent.name
        Row fTitle = sheet.createRow(r++);
        createCell(fTitle, 0, "F: Agent 活跃排名 (Top 10)", headerStyle);
        String[] rankingCols = {"Agent", "对话数", "消息数", "Token消耗"};
        Row rHead = sheet.createRow(r++);
        for (int i = 0; i < rankingCols.length; i++) createCell(rHead, i, rankingCols[i], headerStyle);

        // Load conversations to map convId → agentId
        List<ConversationEntity> rankConvs = conversationMapper.selectList(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getDeleted, 0)
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime)
                .select(ConversationEntity::getConversationId, ConversationEntity::getAgentId));
        Map<String, Long> convAgentMap = rankConvs.stream()
            .filter(c -> c.getAgentId() != null)
            .collect(Collectors.toMap(ConversationEntity::getConversationId, ConversationEntity::getAgentId, (a, b) -> a));
        Map<Long, String> agentIdToName = agentMapper.selectList(
            new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getDeleted, 0))
            .stream().collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName, (a, b) -> a));

        LambdaQueryWrapper<MessageEntity> rankW = new LambdaQueryWrapper<MessageEntity>()
            .eq(MessageEntity::getRole, "assistant")
            .ge(MessageEntity::getCreateTime, startTime)
            .le(MessageEntity::getCreateTime, endTime)
            .eq(MessageEntity::getDeleted, 0)
            .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens, MessageEntity::getConversationId);
        List<MessageEntity> rankMsgs = messageMapper.selectList(rankW);

        Map<String, long[]> agentMap = new LinkedHashMap<>();
        Map<String, Set<String>> agentConvSet = new LinkedHashMap<>();
        for (MessageEntity m : rankMsgs) {
            Long agId = convAgentMap.get(m.getConversationId());
            String agentName = agId != null ? agentIdToName.getOrDefault(agId, "Agent#" + agId) : "-";
            long[] acc = agentMap.computeIfAbsent(agentName, k -> new long[3]);
            acc[0]++; // message count
            acc[1] += m.getPromptTokens() != null ? m.getPromptTokens() : 0;
            acc[1] += m.getCompletionTokens() != null ? m.getCompletionTokens() : 0;
            agentConvSet.computeIfAbsent(agentName, k -> new HashSet<>()).add(m.getConversationId());
        }
        final int[] rr = {r};
        agentMap.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(10)
            .forEach(e -> {
                Row row = sheet.createRow(rr[0]++);
                createCell(row, 0, e.getKey(), null);
                createCell(row, 1, agentConvSet.getOrDefault(e.getKey(), Set.of()).size(), null);
                createCell(row, 2, e.getValue()[0], null);
                createCell(row, 3, e.getValue()[1], null);
            });
        r = rr[0];

        // Auto-size
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 30 * 256);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 2: Token用量
    // ══════════════════════════════════════════════════

    private void buildTokenSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle,
                                 LocalDate start, LocalDate end) {
        Sheet sheet = wb.createSheet("Token用量");
        sheet.createFreezePane(0, 1);
        String[] cols = {"日期", "Provider", "Prompt Tokens", "Completion Tokens", "总Tokens", "消息数", "平均Tokens/消息"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        // 按日聚合 byModel
        LocalDateTime startTime = start.atStartOfDay();
        LocalDateTime endTime = end.atTime(LocalTime.MAX);
        LambdaQueryWrapper<MessageEntity> w = new LambdaQueryWrapper<MessageEntity>()
            .eq(MessageEntity::getRole, "assistant")
            .ge(MessageEntity::getCreateTime, startTime)
            .le(MessageEntity::getCreateTime, endTime)
            .eq(MessageEntity::getDeleted, 0)
            .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens,
                    MessageEntity::getRuntimeProvider, MessageEntity::getCreateTime, MessageEntity::getRuntimeModel);
        List<MessageEntity> msgs = messageMapper.selectList(w);

        Map<String, long[]> byDateProv = new TreeMap<>();
        Map<String, Long> byDateMsg = new TreeMap<>();
        for (MessageEntity m : msgs) {
            String date = m.getCreateTime().toLocalDate().toString();
            String prov = (m.getRuntimeProvider() != null && !m.getRuntimeProvider().isBlank())
                ? m.getRuntimeProvider() : "-";
            String key = date + "|" + prov;
            long[] acc = byDateProv.computeIfAbsent(key, k -> new long[3]);
            acc[0] += m.getPromptTokens() != null ? m.getPromptTokens() : 0;
            acc[1] += m.getCompletionTokens() != null ? m.getCompletionTokens() : 0;
            acc[2]++;
            byDateMsg.merge(date, 1L, Long::sum);
        }

        int r = 1;
        for (Map.Entry<String, long[]> e : byDateProv.entrySet()) {
            // split with limit -1 keeps a trailing empty field (provider "-" guards
            // blanks, but never rely on split dropping trailing segments).
            String[] parts = e.getKey().split("\\|", -1);
            long[] v = e.getValue();
            Row row = sheet.createRow(r++);
            createCell(row, 0, parts[0], null);
            createCell(row, 1, parts[1], null);
            createCell(row, 2, v[0], numStyle);
            createCell(row, 3, v[1], numStyle);
            createCell(row, 4, v[0] + v[1], numStyle);
            createCell(row, 5, v[2], numStyle);
            createCell(row, 6, v[2] > 0 ? String.format("%.0f", (double)(v[0] + v[1]) / v[2]) : "-", null);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 3: 技能统计
    // ══════════════════════════════════════════════════

    private void buildSkillSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle,
                                 CellStyle dateStyle, LocalDateTime startTime, LocalDateTime endTime) {
        Sheet sheet = wb.createSheet("技能统计");
        sheet.createFreezePane(0, 1);
        String[] cols = {"技能ID", "技能名称", "类型", "生命周期", "工作区ID", "工作区名称", "启用", "描述", "最近调用时间", "调用次数", "绑定Agent"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        List<SkillEntity> skills = skillMapper.selectList(
            new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getDeleted, 0));

        // usage stats
        List<SkillUsageStatEntity> usageRows = skillUsageStatMapper.selectList(
            new LambdaQueryWrapper<SkillUsageStatEntity>()
                .ge(SkillUsageStatEntity::getLastLoadedAt, startTime)
                .le(SkillUsageStatEntity::getLastLoadedAt, endTime));
        Map<String, long[]> usageByName = new HashMap<>();
        for (SkillUsageStatEntity u : usageRows) {
            long[] acc = usageByName.computeIfAbsent(u.getSkillName(), k -> new long[]{0, 0});
            acc[0] += u.getLoadCount() != null ? u.getLoadCount() : 0;
            acc[1] = Math.max(acc[1], u.getLastLoadedAt() != null
                ? u.getLastLoadedAt().toEpochSecond(ZoneOffset.UTC) : 0);
        }

        // agent bindings
        List<AgentSkillBinding> bindings = agentSkillBindingMapper.selectList(
            new LambdaQueryWrapper<AgentSkillBinding>().eq(AgentSkillBinding::getEnabled, true));
        Map<Long, List<String>> agentsBySkill = new HashMap<>();
        for (AgentSkillBinding b : bindings) {
            agentsBySkill.computeIfAbsent(b.getSkillId(), k -> new ArrayList<>())
                .add(String.valueOf(b.getAgentId()));
        }
        // resolve agent names
        List<Long> allAgentIds = bindings.stream().map(AgentSkillBinding::getAgentId).distinct().toList();
        Map<Long, String> agentNames = new HashMap<>();
        if (!allAgentIds.isEmpty()) {
            agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>().in(AgentEntity::getId, allAgentIds))
                .forEach(a -> agentNames.put(a.getId(), a.getName()));
        }

        Map<Long, String> wsNames = workspaceMapper.selectList(new LambdaQueryWrapper<WorkspaceEntity>())
            .stream().collect(Collectors.toMap(WorkspaceEntity::getId, WorkspaceEntity::getName, (a, b) -> a));

        int r = 1;
        for (SkillEntity s : skills) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, s.getId(), null);
            createCell(row, 1, s.getName(), null);
            createCell(row, 2, label(s.getSkillType()), null);
            createCell(row, 3, label(s.getLifecycleState()), null);
            createCell(row, 4, s.getWorkspaceId(), null);
            createCell(row, 5, wsNames.getOrDefault(s.getWorkspaceId(), ""), null);
            createCell(row, 6, label(Boolean.TRUE.equals(s.getEnabled()) ? "TRUE" : "FALSE"), null);
            createCell(row, 7, s.getDescription(), null);
            long[] usage = usageByName.get(s.getName());
            if (usage != null && usage[1] > 0) {
                createCell(row, 8, LocalDateTime.ofEpochSecond(usage[1], 0, ZoneOffset.UTC), dateStyle);
                createCell(row, 9, usage[0], numStyle);
            } else {
                createCell(row, 8, "", null);
                createCell(row, 9, 0, numStyle);
            }
            List<String> boundAgents = agentsBySkill.getOrDefault(s.getId(), List.of());
            createCell(row, 10, boundAgents.isEmpty() ? "" :
                boundAgents.stream().map(id -> agentNames.getOrDefault(Long.valueOf(id), id.toString())).collect(Collectors.joining(", ")), null);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 4: 用户统计
    // ══════════════════════════════════════════════════

    private void buildUserStatSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle, CellStyle dateStyle,
                                    LocalDateTime startTime, LocalDateTime endTime) {
        Sheet sheet = wb.createSheet("用户统计");
        sheet.createFreezePane(0, 1);
        String[] cols = {"工作区ID", "工作区名称", "用户ID", "用户名", "角色", "对话数", "Prompt Tokens", "Completion Tokens", "总Tokens", "总耗时(s)", "用户消息数", "平均会话时长(s)", "最后活跃时间"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        // Query conversations in range
        List<ConversationEntity> convs = conversationMapper.selectList(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getDeleted, 0)
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime)
                .select(ConversationEntity::getConversationId, ConversationEntity::getWorkspaceId,
                        ConversationEntity::getUsername, ConversationEntity::getCreateTime,
                        ConversationEntity::getLastActiveTime));

        // Group by (workspace_id, username)
        Map<String, long[]> userMap = new LinkedHashMap<>();
        Map<String, Set<String>> userConvIds = new LinkedHashMap<>();
        Map<String, Long> userDuration = new LinkedHashMap<>();
        Map<String, LocalDateTime> userLastActive = new LinkedHashMap<>();
        for (ConversationEntity c : convs) {
            String key = (c.getWorkspaceId() != null ? c.getWorkspaceId() : 0) + "|"
                + (c.getUsername() != null && !c.getUsername().isBlank() ? c.getUsername() : "-");
            userConvIds.computeIfAbsent(key, k -> new HashSet<>()).add(c.getConversationId());
            if (c.getCreateTime() != null && c.getLastActiveTime() != null) {
                userDuration.merge(key, (long) Duration.between(c.getCreateTime(), c.getLastActiveTime()).getSeconds(), Long::sum);
            }
            if (c.getLastActiveTime() != null) {
                userLastActive.merge(key, c.getLastActiveTime(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        // Load workspaces and users
        Map<Long, String> wsNames = workspaceMapper.selectList(new LambdaQueryWrapper<WorkspaceEntity>())
            .stream().collect(Collectors.toMap(WorkspaceEntity::getId, WorkspaceEntity::getName, (a, b) -> a));
        Map<String, UserEntity> users = userMapper.selectList(new LambdaQueryWrapper<UserEntity>())
            .stream().collect(Collectors.toMap(UserEntity::getUsername, u -> u, (a, b) -> a));

        // Query messages for token sums
        if (!convs.isEmpty()) {
            List<String> allConvIds = convs.stream().map(ConversationEntity::getConversationId).distinct().toList();
            List<MessageEntity> msgs = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                    .in(MessageEntity::getConversationId, allConvIds)
                    .eq(MessageEntity::getDeleted, 0)
                    .select(MessageEntity::getConversationId, MessageEntity::getPromptTokens,
                            MessageEntity::getCompletionTokens, MessageEntity::getRole));
            // Aggregate by (ws, username)
            // Re-query to get the user mapping... For simplicity, aggregate in memory
            Map<String, String> convWsMap = convs.stream().collect(Collectors.toMap(
                ConversationEntity::getConversationId, c -> (c.getWorkspaceId() != null ? c.getWorkspaceId() : 0L) + "|"
                    + (c.getUsername() != null && !c.getUsername().isBlank() ? c.getUsername() : "-"), (a, b) -> a));
            for (MessageEntity m : msgs) {
                String key = convWsMap.get(m.getConversationId());
                if (key == null) continue;
                long[] acc = userMap.computeIfAbsent(key, k -> new long[5]);
                if ("assistant".equals(m.getRole())) {
                    acc[0] += m.getPromptTokens() != null ? m.getPromptTokens() : 0;
                    acc[1] += m.getCompletionTokens() != null ? m.getCompletionTokens() : 0;
                    acc[2]++;
                } else if ("user".equals(m.getRole())) {
                    acc[3]++;
                }
            }
        }

        int r = 1;
        for (String key : userConvIds.keySet()) {
            String[] parts = key.split("\\|", -1);
            long wsId = Long.parseLong(parts[0]);
            String uname = parts[1];
            long[] acc = userMap.getOrDefault(key, new long[5]);
            Row row = sheet.createRow(r++);
            createCell(row, 0, wsId, null);
            createCell(row, 1, wsNames.getOrDefault(wsId, ""), null);
            UserEntity u = users.get(uname);
            createCell(row, 2, u != null ? u.getId() : 0, null);
            createCell(row, 3, uname, null);
            createCell(row, 4, u != null ? u.getRole() : "", null);
            createCell(row, 5, userConvIds.get(key).size(), numStyle);
            createCell(row, 6, acc[0], numStyle);
            createCell(row, 7, acc[1], numStyle);
            createCell(row, 8, acc[0] + acc[1], numStyle);
            createCell(row, 9, userDuration.getOrDefault(key, 0L), numStyle);
            createCell(row, 10, acc[3], numStyle);
            long durSec = userDuration.getOrDefault(key, 0L);
            long convCount = userConvIds.get(key).size();
            createCell(row, 11, convCount > 0 ? String.format("%.1f", (double) durSec / convCount) : "-", null);
            createCell(row, 12, userLastActive.get(key) != null ? userLastActive.get(key) : "", dateStyle);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 5: 用户对话（明细）
    // ══════════════════════════════════════════════════

    private void buildUserDetailSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle,
                                      CellStyle dateStyle, LocalDateTime startTime, LocalDateTime endTime,
                                      boolean enforceLimit) {
        Sheet sheet = wb.createSheet("用户对话");
        sheet.createFreezePane(0, 1);
        String[] cols = {"工作区ID", "工作区名称", "用户ID", "用户名", "角色", "Agent名称", "会话ID", "消息时间", "用户内容", "响应Tokens", "响应模型", "响应Provider", "响应耗时(s)"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        // Load convs in range
        List<ConversationEntity> convs = conversationMapper.selectList(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getDeleted, 0)
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime)
                .select(ConversationEntity::getConversationId, ConversationEntity::getWorkspaceId,
                        ConversationEntity::getUsername, ConversationEntity::getAgentId));
        if (convs.isEmpty()) { for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i); return; }

        Map<String, Long> convWs = convs.stream().collect(Collectors.toMap(
            ConversationEntity::getConversationId, c -> c.getWorkspaceId() != null ? c.getWorkspaceId() : 0, (a, b) -> a));
        Map<String, String> convUser = convs.stream().collect(Collectors.toMap(
            ConversationEntity::getConversationId, c -> c.getUsername() != null ? c.getUsername() : "-", (a, b) -> a));
        Map<String, Long> convAgent = convs.stream().filter(c -> c.getAgentId() != null).collect(Collectors.toMap(
            ConversationEntity::getConversationId, ConversationEntity::getAgentId, (a, b) -> a));
        Map<Long, String> wsNames = workspaceMapper.selectList(new LambdaQueryWrapper<WorkspaceEntity>())
            .stream().collect(Collectors.toMap(WorkspaceEntity::getId, WorkspaceEntity::getName, (a, b) -> a));
        Map<String, UserEntity> userMap = userMapper.selectList(new LambdaQueryWrapper<UserEntity>())
            .stream().collect(Collectors.toMap(UserEntity::getUsername, u -> u, (a, b) -> a));
        Map<Long, String> agentNames = agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getDeleted, 0))
            .stream().collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName, (a, b) -> a));

        List<String> convIds = convs.stream().map(ConversationEntity::getConversationId).toList();
        List<MessageEntity> msgs = messageMapper.selectList(
            new LambdaQueryWrapper<MessageEntity>()
                .in(MessageEntity::getConversationId, convIds)
                .eq(MessageEntity::getDeleted, 0)
                .orderByAsc(MessageEntity::getConversationId)
                .orderByAsc(MessageEntity::getCreateTime)
                .select(MessageEntity::getConversationId, MessageEntity::getRole, MessageEntity::getCreateTime,
                        MessageEntity::getContent, MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens,
                        MessageEntity::getRuntimeModel, MessageEntity::getRuntimeProvider));

        // Pair user messages with next assistant
        int r = 1;
        for (int i = 0; i < msgs.size() - 1 && (!enforceLimit || r <= LIMIT_USER_MSGS); i++) {
            MessageEntity m = msgs.get(i);
            if (!"user".equals(m.getRole())) continue;
            MessageEntity next = msgs.get(i + 1);
            boolean isAssistant = "assistant".equals(next.getRole());
            Row row = sheet.createRow(r++);
            Long wsId = convWs.get(m.getConversationId());
            String uname = convUser.get(m.getConversationId());
            UserEntity u = userMap.get(uname);
            createCell(row, 0, wsId != null ? wsId : 0, null);
            createCell(row, 1, wsNames.getOrDefault(wsId, ""), null);
            createCell(row, 2, u != null ? u.getId() : 0, null);
            createCell(row, 3, uname, null);
            createCell(row, 4, u != null ? u.getRole() : "", null);
            Long agId = convAgent.get(m.getConversationId());
            createCell(row, 5, agId != null ? agentNames.getOrDefault(agId, "-") : "-", null);
            createCell(row, 6, m.getConversationId(), null);
            createCell(row, 7, m.getCreateTime(), dateStyle);
            createCell(row, 8, m.getContent(), null);
            if (isAssistant) {
                createCell(row, 9,
                    (long)(next.getCompletionTokens() != null ? next.getCompletionTokens() : 0), numStyle);
                createCell(row, 10, next.getRuntimeModel(), null);
                createCell(row, 11, next.getRuntimeProvider(), null);
                createCell(row, 12, Duration.between(m.getCreateTime(), next.getCreateTime()).toMillis() / 1000.0, numStyle);
            }
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 6: 安全与审计
    // ══════════════════════════════════════════════════

    private void buildSecuritySheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle dateStyle,
                                    LocalDateTime startTime, LocalDateTime endTime, boolean enforceLimit) {
        Sheet sheet = wb.createSheet("安全与审计");
        sheet.createFreezePane(0, 1);
        String[] cols = {"来源", "ID", "工作区ID", "工具名/资源类型", "严重级别", "决策", "状态", "操作人", "分类", "授权范围类型", "授权范围ID", "描述", "时间", "资源类型", "操作", "详情"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        int r = 1;
        r = appendGuardRules(sheet, r, cols.length, dateStyle);
        r = appendGuardAuditLog(sheet, r, cols.length, startTime, endTime, dateStyle);
        r = appendApprovals(sheet, r, cols.length, startTime, endTime, dateStyle);
        r = appendGrants(sheet, r, cols.length, dateStyle);
        r = appendGuardConfig(sheet, r, cols.length, dateStyle);
        if (enforceLimit) r = Math.min(r, LIMIT_AUDIT + 1);
        // business audit events
        List<AuditEventEntity> auditEvents = auditEventMapper.selectList(
            new LambdaQueryWrapper<AuditEventEntity>()
                .ge(AuditEventEntity::getCreateTime, startTime)
                .le(AuditEventEntity::getCreateTime, endTime)
                .orderByDesc(AuditEventEntity::getCreateTime)
                .last(enforceLimit ? "LIMIT " + LIMIT_AUDIT : ""));
        for (AuditEventEntity ae : auditEvents) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "business_audit", null);
            createCell(row, 1, ae.getId(), null);
            createCell(row, 2, ae.getWorkspaceId(), null);
            createCell(row, 3, "", null);
            createCell(row, 4, "", null);
            createCell(row, 5, "", null);
            createCell(row, 6, "", null);
            createCell(row, 7, ae.getUsername(), null);
            createCell(row, 8, "", null);
            createCell(row, 9, "", null);
            createCell(row, 10, "", null);
            createCell(row, 11, "", null);
            createCell(row, 12, ae.getCreateTime(), dateStyle);
            createCell(row, 13, label(ae.getResourceType()), null);
            createCell(row, 14, label(ae.getAction()), null);
            createCell(row, 15, ae.getDetailJson(), null);
        }

        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    private int appendGuardRules(Sheet sheet, int r, int maxCols, CellStyle dateStyle) {
        List<ToolGuardRuleEntity> rules = toolGuardRuleMapper.selectList(
            new LambdaQueryWrapper<ToolGuardRuleEntity>().eq(ToolGuardRuleEntity::getDeleted, 0));
        for (ToolGuardRuleEntity rl : rules) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "rule", null);
            createCell(row, 1, rl.getId(), null);
            createCell(row, 2, null, null);
            createCell(row, 3, rl.getToolName(), null);
            createCell(row, 4, rl.getSeverity() != null ? rl.getSeverity().toLowerCase() : "", null);
            createCell(row, 5, rl.getDecision(), null);
            createCell(row, 6, Boolean.TRUE.equals(rl.getEnabled()) ? "enabled" : "disabled", null);
            createCell(row, 7, "", null);
            createCell(row, 8, rl.getCategory(), null);
            createCell(row, 9, "", null);
            createCell(row, 10, "", null);
            createCell(row, 11, rl.getDescription(), null);
            createCell(row, 12, rl.getCreateTime(), dateStyle);
            createCell(row, 13, "", null);
            createCell(row, 14, "", null);
            createCell(row, 15, "", null);
        }
        return r;
    }

    private int appendGuardAuditLog(Sheet sheet, int r, int maxCols, LocalDateTime start, LocalDateTime end, CellStyle dateStyle) {
        List<ToolGuardAuditLogEntity> logs = toolGuardAuditLogMapper.selectList(
            new LambdaQueryWrapper<ToolGuardAuditLogEntity>()
                .ge(ToolGuardAuditLogEntity::getCreateTime, start)
                .le(ToolGuardAuditLogEntity::getCreateTime, end));
        Map<Long, String> userIdToName = userMapper.selectList(new LambdaQueryWrapper<UserEntity>())
            .stream().collect(Collectors.toMap(UserEntity::getId, UserEntity::getUsername, (a, b) -> a));
        for (ToolGuardAuditLogEntity l : logs) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "audit", null);
            createCell(row, 1, l.getId(), null);
            createCell(row, 2, null, null);
            createCell(row, 3, l.getToolName(), null);
            createCell(row, 4, l.getMaxSeverity() != null ? l.getMaxSeverity().toLowerCase() : "", null);
            createCell(row, 5, l.getDecision(), null);
            createCell(row, 6, "", null);
            createCell(row, 7, l.getUserId() != null ? userIdToName.getOrDefault(l.getUserId(), String.valueOf(l.getUserId())) : "", null);
            createCell(row, 8, "", null);
            createCell(row, 9, "", null);
            createCell(row, 10, "", null);
            createCell(row, 11, "", null);
            createCell(row, 12, l.getCreateTime(), dateStyle);
            createCell(row, 13, "", null);
            createCell(row, 14, "", null);
            createCell(row, 15, l.getFindingsJson(), null);
        }
        return r;
    }

    private int appendApprovals(Sheet sheet, int r, int maxCols, LocalDateTime start, LocalDateTime end, CellStyle dateStyle) {
        List<ToolApprovalEntity> approvals = toolApprovalMapper.selectList(
            new LambdaQueryWrapper<ToolApprovalEntity>()
                .ge(ToolApprovalEntity::getCreateTime, start)
                .le(ToolApprovalEntity::getCreateTime, end));
        for (ToolApprovalEntity a : approvals) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "approval", null);
            createCell(row, 1, a.getId(), null);
            createCell(row, 2, null, null);
            createCell(row, 3, a.getToolName(), null);
            createCell(row, 4, a.getMaxSeverity() != null ? a.getMaxSeverity().toLowerCase() : "", null);
            createCell(row, 5, "", null);
            createCell(row, 6, a.getStatus(), null);
            createCell(row, 7, a.getRequesterName(), null);
            createCell(row, 8, "", null);
            createCell(row, 9, "", null);
            createCell(row, 10, "", null);
            createCell(row, 11, a.getSummary(), null);
            createCell(row, 12, a.getCreateTime(), dateStyle);
            createCell(row, 13, "", null);
            createCell(row, 14, "", null);
            createCell(row, 15, "", null);
        }
        return r;
    }

    private int appendGrants(Sheet sheet, int r, int maxCols, CellStyle dateStyle) {
        List<ApprovalGrant> grants = approvalGrantMapper.selectList(
            new LambdaQueryWrapper<ApprovalGrant>().eq(ApprovalGrant::getDeleted, 0));
        for (ApprovalGrant g : grants) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "grant", null);
            createCell(row, 1, g.getId(), null);
            createCell(row, 2, g.getWorkspaceId(), null);
            createCell(row, 3, g.getToolName(), null);
            createCell(row, 4, g.getMaxSeverity(), null);
            createCell(row, 5, "", null);
            createCell(row, 6, g.getRevoked() != null && g.getRevoked() == 0 ? "active" : "revoked", null);
            createCell(row, 7, g.getGrantedBy() != null ? String.valueOf(g.getGrantedBy()) : "", null);
            createCell(row, 8, "", null);
            createCell(row, 9, g.getScopeType(), null);
            createCell(row, 10, g.getScopeId(), null);
            createCell(row, 11, g.getNote(), null);
            createCell(row, 12, g.getCreateTime(), dateStyle);
            createCell(row, 13, "", null);
            createCell(row, 14, "", null);
            createCell(row, 15, "", null);
        }
        return r;
    }

    private int appendGuardConfig(Sheet sheet, int r, int maxCols, CellStyle dateStyle) {
        List<ToolGuardConfigEntity> configs = toolGuardConfigMapper.selectList(new LambdaQueryWrapper<>());
        for (ToolGuardConfigEntity c : configs) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, "config", null);
            createCell(row, 1, c.getId(), null);
            createCell(row, 2, null, null);
            createCell(row, 3, "", null);
            createCell(row, 4, "", null);
            createCell(row, 5, "", null);
            createCell(row, 6, Boolean.TRUE.equals(c.getEnabled()) ? "enabled" : "disabled", null);
            createCell(row, 7, "", null);
            createCell(row, 8, "", null);
            createCell(row, 9, "", null);
            createCell(row, 10, "", null);
            createCell(row, 11, "", null);
            createCell(row, 12, c.getCreateTime(), dateStyle);
            createCell(row, 13, "", null);
            createCell(row, 14, "", null);
            createCell(row, 15, "guard_scope=" + (c.getGuardScope() != null ? c.getGuardScope() : "-")
                + "; file_guard=" + (c.getFileGuardEnabled() != null ? c.getFileGuardEnabled() : "-")
                + "; retention=" + (c.getAuditRetentionDays() != null ? c.getAuditRetentionDays() : "-") + "d", null);
        }
        return r;
    }

    // ══════════════════════════════════════════════════
    //  Sheet 7: 渠道统计
    // ══════════════════════════════════════════════════

    private void buildChannelSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle,
                                   LocalDateTime startTime, LocalDateTime endTime) {
        Sheet sheet = wb.createSheet("渠道统计");
        sheet.createFreezePane(0, 1);
        String[] cols = {"渠道ID", "渠道名称", "渠道类型", "工作区ID", "启用", "绑定Agent", "对话数", "总Tokens", "独立用户数"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        List<ChannelEntity> channels = channelMapper.selectList(
            new LambdaQueryWrapper<ChannelEntity>().eq(ChannelEntity::getDeleted, 0));
        Map<Long, String> agentNames = new HashMap<>();
        for (ChannelEntity ch : channels) {
            if (ch.getAgentId() != null) {
                AgentEntity ag = agentMapper.selectById(ch.getAgentId());
                if (ag != null) agentNames.put(ch.getAgentId(), ag.getName());
            }
        }

        int r = 1;
        for (ChannelEntity ch : channels) {
            long conversations = 0, tokens = 0, uniqueUsers = 0;
            if (ch.getAgentId() != null) {
                // count conversations for this agent
                conversations = conversationMapper.selectCount(
                    new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getAgentId, ch.getAgentId())
                        .eq(ConversationEntity::getDeleted, 0)
                        .ge(ConversationEntity::getCreateTime, startTime)
                        .le(ConversationEntity::getCreateTime, endTime));
                // sum tokens
                List<String> agentConvIds = conversationMapper.selectList(
                    new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getAgentId, ch.getAgentId())
                        .eq(ConversationEntity::getDeleted, 0)
                        .ge(ConversationEntity::getCreateTime, startTime)
                        .le(ConversationEntity::getCreateTime, endTime)
                        .select(ConversationEntity::getConversationId))
                    .stream().map(ConversationEntity::getConversationId).toList();
                if (!agentConvIds.isEmpty()) {
                    List<MessageEntity> msgs = messageMapper.selectList(
                        new LambdaQueryWrapper<MessageEntity>()
                            .in(MessageEntity::getConversationId, agentConvIds)
                            .eq(MessageEntity::getRole, "assistant")
                            .eq(MessageEntity::getDeleted, 0)
                            .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens));
                    for (MessageEntity m : msgs) {
                        tokens += (m.getPromptTokens() != null ? m.getPromptTokens() : 0)
                                + (m.getCompletionTokens() != null ? m.getCompletionTokens() : 0);
                    }
                    uniqueUsers = conversationMapper.selectList(
                        new LambdaQueryWrapper<ConversationEntity>()
                            .in(ConversationEntity::getConversationId, agentConvIds)
                            .select(ConversationEntity::getUsername))
                        .stream().map(ConversationEntity::getUsername).filter(Objects::nonNull).distinct().count();
                }
            }
            Row row = sheet.createRow(r++);
            createCell(row, 0, ch.getId(), null);
            createCell(row, 1, ch.getName(), null);
            createCell(row, 2, ch.getChannelType(), null);
            createCell(row, 3, ch.getWorkspaceId(), null);
            createCell(row, 4, label(Boolean.TRUE.equals(ch.getEnabled()) ? "TRUE" : "FALSE"), null);
            createCell(row, 5, agentNames.getOrDefault(ch.getAgentId(), ""), null);
            createCell(row, 6, conversations, numStyle);
            createCell(row, 7, tokens, numStyle);
            createCell(row, 8, uniqueUsers, numStyle);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 8: 模型配置
    // ══════════════════════════════════════════════════

    private void buildModelSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle numStyle) {
        Sheet sheet = wb.createSheet("模型配置");
        sheet.createFreezePane(0, 1);
        String[] cols = {"Provider ID", "Provider名称", "模型名称", "模型类型", "启用", "Temperature", "Max Tokens", "默认模型", "最大输入Tokens", "描述"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        List<ModelConfigEntity> configs = modelConfigMapper.selectList(
            new LambdaQueryWrapper<ModelConfigEntity>().eq(ModelConfigEntity::getDeleted, 0));
        List<ModelProviderEntity> allProvs = modelProviderMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, String> provNames = allProvs.stream().collect(Collectors.toMap(ModelProviderEntity::getProviderId, ModelProviderEntity::getName, (a, b) -> a));
        Set<String> configuredProvIds = allProvs.stream()
            .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
            .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
            .map(ModelProviderEntity::getProviderId)
            .collect(Collectors.toSet());

        int r = 1;
        for (ModelConfigEntity c : configs) {
            if (!configuredProvIds.contains(c.getProvider())) continue;
            Row row = sheet.createRow(r++);
            createCell(row, 0, c.getProvider(), null);
            createCell(row, 1, provNames.getOrDefault(c.getProvider(), ""), null);
            createCell(row, 2, c.getModelName(), null);
            createCell(row, 3, label(c.getModelType() != null ? c.getModelType() : "-"), null);
            createCell(row, 4, label(Boolean.TRUE.equals(c.getEnabled()) ? "TRUE" : "FALSE"), null);
            if (c.getTemperature() != null) createCell(row, 5, c.getTemperature().doubleValue(), numStyle);
            else createCell(row, 5, "", null);
            if (c.getMaxTokens() != null) createCell(row, 6, c.getMaxTokens(), numStyle);
            else createCell(row, 6, "", null);
            createCell(row, 7, Boolean.TRUE.equals(c.getIsDefault()) ? "是" : "否", null);
            createCell(row, 8, c.getMaxInputTokens() != null ? c.getMaxInputTokens() : "", numStyle);
            createCell(row, 9, c.getDescription(), null);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  Sheet 9: 定时任务
    // ══════════════════════════════════════════════════

    private void buildCronSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle dateStyle,
                                LocalDateTime startTime, LocalDateTime endTime) {
        Sheet sheet = wb.createSheet("定时任务");
        sheet.createFreezePane(0, 1);
        String[] cols = {"任务ID", "任务名称", "触发方式", "状态", "耗时(秒)", "Token消耗", "执行时间"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) createCell(header, i, cols[i], headerStyle);

        List<CronJobRunEntity> runs = cronJobRunMapper.selectList(
            new LambdaQueryWrapper<CronJobRunEntity>()
                .ge(CronJobRunEntity::getStartedAt, startTime)
                .le(CronJobRunEntity::getStartedAt, endTime)
                .orderByDesc(CronJobRunEntity::getStartedAt));

        Map<Long, String> jobNames = cronJobMapper.selectList(new LambdaQueryWrapper<CronJobEntity>())
            .stream().collect(Collectors.toMap(CronJobEntity::getId, CronJobEntity::getName, (a, b) -> a));

        int r = 1;
        for (CronJobRunEntity run : runs) {
            Row row = sheet.createRow(r++);
            createCell(row, 0, run.getCronJobId(), null);
            createCell(row, 1, jobNames.getOrDefault(run.getCronJobId(), ""), null);
            createCell(row, 2, run.getTriggerType(), null);
            createCell(row, 3, run.getStatus(), null);
            if (run.getStartedAt() != null && run.getFinishedAt() != null) {
                createCell(row, 4, Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis() / 1000.0, null);
            } else {
                createCell(row, 4, "", null);
            }
            createCell(row, 5, run.getTokenUsage() != null ? run.getTokenUsage() : "", null);
            createCell(row, 6, run.getStartedAt(), dateStyle);
        }
        for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);
    }

    // ══════════════════════════════════════════════════
    //  工具方法
    // ══════════════════════════════════════════════════

    private Map<String, Object> queryOverviewStats(Long workspaceId, LocalDateTime start, LocalDateTime end) {
        // Reuse DashboardService logic but with custom time range
        // Simplified: just query counts and tokens
        LambdaQueryWrapper<ConversationEntity> cw = new LambdaQueryWrapper<ConversationEntity>()
            .eq(ConversationEntity::getDeleted, 0)
            .ge(ConversationEntity::getCreateTime, start)
            .le(ConversationEntity::getCreateTime, end);
        long conversations = conversationMapper.selectCount(cw);

        List<String> convIds = conversationMapper.selectList(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getDeleted, 0)
                .ge(ConversationEntity::getCreateTime, start)
                .le(ConversationEntity::getCreateTime, end)
                .select(ConversationEntity::getConversationId))
            .stream().map(ConversationEntity::getConversationId).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversations", conversations);
        if (convIds.isEmpty()) {
            stats.put("messages", 0L);
            stats.put("totalTokens", 0L);
            stats.put("toolCalls", 0L);
            return stats;
        }

        long messages = messageMapper.selectCount(
            new LambdaQueryWrapper<MessageEntity>().in(MessageEntity::getConversationId, convIds).eq(MessageEntity::getDeleted, 0));
        stats.put("messages", messages);

        List<MessageEntity> assistantMsgs = messageMapper.selectList(
            new LambdaQueryWrapper<MessageEntity>()
                .in(MessageEntity::getConversationId, convIds)
                .eq(MessageEntity::getRole, "assistant")
                .eq(MessageEntity::getDeleted, 0)
                .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens, MessageEntity::getMetadata));
        long totalTokens = 0, toolCalls = 0;
        for (MessageEntity m : assistantMsgs) {
            totalTokens += (m.getPromptTokens() != null ? m.getPromptTokens() : 0)
                         + (m.getCompletionTokens() != null ? m.getCompletionTokens() : 0);
            toolCalls += countToolCallsFromMetadata(m.getMetadata());
        }
        stats.put("totalTokens", totalTokens);
        stats.put("toolCalls", toolCalls);
        return stats;
    }

    private String label(String raw) {
        if (raw == null) return "";
        return LABEL_MAP.getOrDefault(raw, raw);
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        return 0L;
    }

    private long countToolCallsFromMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank() || "{}".equals(metadataJson.trim())) {
            return 0;
        }
        try {
            String json = metadataJson.trim();
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = objectMapper.readValue(json, String.class);
            }
            if (json.isBlank() || "{}".equals(json)) return 0;
            JsonNode root = objectMapper.readTree(json);
            JsonNode toolCalls = root.get("toolCalls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                return toolCalls.size();
            }
            JsonNode segments = root.get("segments");
            if (segments != null && segments.isArray()) {
                long count = 0;
                for (JsonNode seg : segments) {
                    if ("tool_call".equals(seg.path("type").asText())) count++;
                }
                return count;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private void addKvRow(Sheet sheet, int r, String key, String value, CellStyle style) {
        Row row = sheet.createRow(r);
        Cell k = row.createCell(0);
        k.setCellValue(key);
        if (style != null) k.setCellStyle(style);
        row.createCell(1).setCellValue(value);
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle createNumStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
        return style;
    }

    private CellStyle createDateStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("yyyy-MM-dd HH:mm:ss"));
        return style;
    }

    private void createCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof String s) {
            cell.setCellValue(s);
        } else if (value instanceof Long l) {
            // Snowflake IDs exceed the 2^53-1 exact-integer range of Excel's
            // numeric (double) cells and would be rounded or shown in scientific
            // notation; write out-of-range longs as text to preserve precision.
            if (l > 9007199254740991L || l < -9007199254740991L) {
                cell.setCellValue(String.valueOf(l));
            } else {
                cell.setCellValue((double) l);
            }
        } else if (value instanceof Integer i) {
            cell.setCellValue((double) i);
        } else if (value instanceof Double d) {
            cell.setCellValue(d);
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b.toString());
        } else if (value instanceof LocalDateTime ldt) {
            cell.setCellValue(ldt.format(DT_FMT));
        } else {
            cell.setCellValue(value.toString());
        }
        if (style != null) cell.setCellStyle(style);
    }

    // ══════════════════════════════════════════════════
    //  定时清理
    // ══════════════════════════════════════════════════

    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredTasks() {
        long cutoff = System.currentTimeMillis() - 86400000;
        tasks.values().removeIf(task -> {
            if (task.getCompletedAt() > 0 && task.getCompletedAt() < cutoff) {
                try { Files.deleteIfExists(task.getFilePath()); } catch (IOException ignored) {}
                return true;
            }
            return false;
        });
    }
}
