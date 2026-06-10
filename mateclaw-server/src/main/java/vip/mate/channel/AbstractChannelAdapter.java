package vip.mate.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.health.ChannelHealth;
import vip.mate.channel.model.ChannelEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 渠道适配器抽象基类
 * <p>
 * 渠道适配器抽象基类设计：
 * - 统一的生命周期管理（start/stop/isRunning）
 * - Bot 前缀过滤（群消息中只响应 @bot 或指定前缀的消息）
 * - 配置解析（从 ChannelEntity.configJson 读取渠道特有配置）
 * - 消息路由（通过 ChannelMessageRouter 转发到 Agent）
 *
 * @author MateClaw Team
 */
@Slf4j
public abstract class AbstractChannelAdapter implements ChannelAdapter {

    protected final ChannelEntity channelEntity;
    protected final ChannelMessageRouter messageRouter;
    protected final ObjectMapper objectMapper;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /** 解析后的渠道配置 */
    protected Map<String, Object> config;

    // ==================== 连接状态 & 重连基础设施 ====================

    /** 渠道连接状态 */
    public enum ConnectionState {
        CONNECTED,      // 已连接
        RECONNECTING,   // 重连中
        DISCONNECTED,   // 已断开
        ERROR           // 错误（超过最大重试次数）
    }

    @Getter
    protected final AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    @Getter
    protected volatile String lastError;

    /** 最近一次收到消息或连接成功的时间（用于健康监控） */
    @Getter
    protected final AtomicLong lastEventTimeMs = new AtomicLong(System.currentTimeMillis());

    protected ExponentialBackoff backoff = new ExponentialBackoff();

    /** 重连调度器（懒初始化，仅 IM 渠道使用） */
    protected ScheduledExecutorService reconnectScheduler;

    /** 当前重连任务的 Future（可取消） */
    protected volatile ScheduledFuture<?> reconnectFuture;

    protected AbstractChannelAdapter(ChannelEntity channelEntity,
                                     ChannelMessageRouter messageRouter,
                                     ObjectMapper objectMapper) {
        this.channelEntity = channelEntity;
        this.messageRouter = messageRouter;
        this.objectMapper = objectMapper;
        this.config = parseConfig(channelEntity.getConfigJson());
    }

    /**
     * 获取或创建重连调度器
     */
    protected ScheduledExecutorService ensureReconnectScheduler() {
        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, getChannelType() + "-reconnect-" + channelEntity.getId());
                t.setDaemon(true);
                return t;
            });
        }
        return reconnectScheduler;
    }

    /**
     * 调度一次重连尝试（指数退避延迟）
     * <p>
     * 子类在检测到连接断开时调用此方法。方法内部会：
     * 1. 检查是否已超过最大重试次数
     * 2. 计算下一次重试延迟
     * 3. 通过 ScheduledExecutorService 调度 {@link #doReconnect()}
     */
    protected void scheduleReconnect() {
        if (!running.get()) {
            log.debug("[{}] Not running, skipping reconnect", getChannelType());
            return;
        }

        if (backoff.isExhausted()) {
            connectionState.set(ConnectionState.ERROR);
            lastError = "Max reconnect attempts (" + backoff.getMaxAttempts() + ") exhausted";
            log.error("[{}] {}: {}", getChannelType(), channelEntity.getName(), lastError);
            return;
        }

        connectionState.set(ConnectionState.RECONNECTING);
        long delayMs = backoff.nextDelayMs();
        log.info("[{}] Scheduling reconnect for {} in {}ms (attempt #{})",
                getChannelType(), channelEntity.getName(), delayMs, backoff.getAttempts());

        reconnectFuture = ensureReconnectScheduler().schedule(() -> {
            if (!running.get()) return;
            try {
                doReconnect();
                onReconnectSuccess();
            } catch (Exception e) {
                onReconnectFailed(e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 实际重连逻辑（子类覆写）
     * <p>
     * 默认实现调用 doStop() + doStart()，子类可覆写以实现更细粒度的重连。
     */
    protected void doReconnect() {
        log.info("[{}] Reconnecting: {}", getChannelType(), channelEntity.getName());
        try {
            doStop();
        } catch (Exception e) {
            log.debug("[{}] doStop during reconnect: {}", getChannelType(), e.getMessage());
        }
        doStart();
    }

    /**
     * 连接断开时调用（子类调用此方法触发重连流程）
     */
    protected void onDisconnected(String reason) {
        if (!running.get()) return;
        lastError = reason;
        log.warn("[{}] Disconnected: {} - {}", getChannelType(), channelEntity.getName(), reason);
        scheduleReconnect();
    }

    /**
     * 重连成功回调
     */
    protected void onReconnectSuccess() {
        backoff.reset();
        connectionState.set(ConnectionState.CONNECTED);
        lastEventTimeMs.set(System.currentTimeMillis());
        lastError = null;
        log.info("[{}] Reconnected successfully: {} (backoff reset)",
                getChannelType(), channelEntity.getName());
    }

    /**
     * 重连失败回调
     */
    protected void onReconnectFailed(Exception e) {
        lastError = e.getMessage();
        log.warn("[{}] Reconnect failed for {}: {} (attempt #{})",
                getChannelType(), channelEntity.getName(), e.getMessage(), backoff.getAttempts());
        scheduleReconnect();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[{}] Starting channel: {}", getChannelType(), channelEntity.getName());
            try {
                doStart();
                connectionState.set(ConnectionState.CONNECTED);
                lastEventTimeMs.set(System.currentTimeMillis());
                lastError = null;
                backoff.reset();
                log.info("[{}] Channel started successfully: {}", getChannelType(), channelEntity.getName());
            } catch (Exception e) {
                running.set(false);
                connectionState.set(ConnectionState.ERROR);
                lastError = e.getMessage();
                log.error("[{}] Failed to start channel {}: {}", getChannelType(), channelEntity.getName(), e.getMessage(), e);
                throw new RuntimeException("Channel start failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[{}] Stopping channel: {}", getChannelType(), channelEntity.getName());
            // 取消挂起的重连任务
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
            if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
                reconnectScheduler.shutdownNow();
                reconnectScheduler = null;
            }
            try {
                doStop();
                log.info("[{}] Channel stopped: {}", getChannelType(), channelEntity.getName());
            } catch (Exception e) {
                log.error("[{}] Error stopping channel {}: {}", getChannelType(), channelEntity.getName(), e.getMessage(), e);
            }
            connectionState.set(ConnectionState.DISCONNECTED);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Map the existing {@code ConnectionState} machine to a typed
     * {@link ChannelHealth} snapshot. The "UP" status requires
     * {@code running=true} AND {@code state==CONNECTED} — this is what
     * makes the green dot honest: "stopped admins" and "started but
     * disconnected" both report something other than UP.
     */
    @Override
    public ChannelHealth health() {
        Long id = channelEntity != null ? channelEntity.getId() : null;
        String type = getChannelType();
        if (!running.get()) {
            return ChannelHealth.outOfService(type, id);
        }
        Instant lastEvent = Instant.ofEpochMilli(lastEventTimeMs.get());
        ConnectionState s = connectionState.get();
        return switch (s) {
            case CONNECTED -> ChannelHealth.up(type, id, lastEvent);
            case RECONNECTING -> ChannelHealth.reconnecting(type, id,
                    lastError != null ? lastError : "reconnecting", lastEvent);
            case ERROR -> ChannelHealth.down(type, id,
                    lastError != null ? lastError : "channel error", lastEvent);
            case DISCONNECTED -> ChannelHealth.down(type, id,
                    "disconnected", lastEvent);
        };
    }

    /**
     * RFC-024 Change 1：刷新"活跃时间"的标准入口。
     *
     * <p>任何代表"连接仍然有效"的事件都应调用本方法：
     * <ul>
     *   <li>收到真实消息（{@link #onMessage}）</li>
     *   <li>长轮询成功返回（即使无消息）—— 例：WeixinChannelAdapter.pollLoop</li>
     *   <li>心跳 ping 成功</li>
     * </ul>
     * 这样 {@code ChannelHealthMonitor} 才能准确区分"连接僵尸但用户没发消息" vs
     * "连接真的死了"，不再依赖消息密度这个稀疏信号。</p>
     */
    protected void touchActivity() {
        lastEventTimeMs.set(System.currentTimeMillis());
    }

    @Override
    public void onMessage(ChannelMessage message) {
        touchActivity();

        // Bot 前缀过滤
        if (!shouldProcess(message)) {
            log.debug("[{}] Message filtered (bot prefix not matched): {}", getChannelType(), message.getContent());
            return;
        }

        // 清理 bot 前缀
        String cleaned = cleanBotPrefix(message.getContent());
        if (cleaned.isBlank() && !hasContentParts(message)) {
            log.debug("[{}] Empty message after prefix cleaning, ignoring", getChannelType());
            return;
        }
        message.setContent(cleaned);

        // 访问控制检查
        if (!checkAccess(message)) {
            return;
        }

        // 路由到 Agent 处理
        messageRouter.enqueue(message, this, channelEntity);
    }

    @Override
    public String getDisplayName() {
        return channelEntity.getName();
    }

    /**
     * 渲染并发送消息：根据 configJson 中的渲染配置过滤内容，按平台限制分割后逐段发送
     */
    @Override
    public void renderAndSend(String targetId, String content) {
        boolean filterThinking = getConfigBoolean("filter_thinking", true);
        boolean filterToolMessages = getConfigBoolean("filter_tool_messages", true);
        String format = getConfigString("message_format", "auto");
        int maxLen = ChannelMessageRenderer.PLATFORM_LIMITS.getOrDefault(getChannelType(), 20000);

        List<String> segments = ChannelMessageRenderer.renderForChannel(
                content, filterThinking, filterToolMessages, format, maxLen);

        for (String segment : segments) {
            sendMessage(targetId, segment);
        }
    }

    /**
     * Approval notice rendering — primary implementation position.
     *
     * <p>Subclasses that support a native card surface (WeCom
     * {@code button_interaction}, DingTalk {@code ActionCard}, etc.)
     * override this method and may call
     * {@code super.sendApprovalNotice(...)} to fall back to the text
     * path on render failure / payload-too-large / etc.
     *
     * <p>Lives on the abstract class rather than as an interface
     * default method so the {@code super.x(...)} call from subclasses
     * resolves cleanly via Java's normal class inheritance — see
     * RFC-32 §2.0.4 (C-4 fix).
     */
    @Override
    public void sendApprovalNotice(String targetId,
            vip.mate.channel.notification.ApprovalNotice notice) {
        sendMessage(targetId,
                vip.mate.channel.notification.ApprovalNotificationService.staticBuildText(notice));
    }

    // ==================== 模板方法（子类实现） ====================

    /**
     * 实际启动逻辑（建立连接、注册 Webhook 等）
     */
    protected abstract void doStart();

    /**
     * 实际停止逻辑（断开连接、清理资源）
     */
    protected abstract void doStop();

    // ==================== Bot 前缀处理 ====================

    /**
     * 判断消息是否需要处理
     * <p>
     * 实现 require_mention / bot_prefix 过滤机制：
     * - 私聊（chatId == null 或等于 senderId）：始终处理
     * - 群聊：如果设置了 botPrefix，只处理以该前缀开头的消息
     */
    protected boolean shouldProcess(ChannelMessage message) {
        String botPrefix = channelEntity.getBotPrefix();
        if (botPrefix == null || botPrefix.isBlank()) {
            return true; // 未设置前缀，处理所有消息
        }

        // 私聊始终处理
        if (isDirectMessage(message)) {
            return true;
        }

        // 群聊检查前缀
        String content = message.getContent();
        return content != null && content.trim().startsWith(botPrefix.trim());
    }

    /**
     * 清理消息中的 bot 前缀
     */
    protected String cleanBotPrefix(String content) {
        if (content == null) return "";
        String botPrefix = channelEntity.getBotPrefix();
        if (botPrefix != null && !botPrefix.isBlank() && content.trim().startsWith(botPrefix.trim())) {
            return content.trim().substring(botPrefix.trim().length()).trim();
        }
        return content.trim();
    }

    /**
     * 检查消息是否包含非文本内容部分（图片、文件、视频等）
     */
    private boolean hasContentParts(ChannelMessage message) {
        if (message.getContentParts() == null || message.getContentParts().isEmpty()) {
            return false;
        }
        return message.getContentParts().stream()
                .anyMatch(p -> p != null && !"text".equals(p.getType()));
    }

    /**
     * 判断是否为私聊消息
     */
    protected boolean isDirectMessage(ChannelMessage message) {
        return message.getChatId() == null
                || message.getChatId().equals(message.getSenderId());
    }

    // ==================== 访问控制 ====================

    /**
     * 检查消息发送者是否有权访问此渠道
     * <p>
     * 基于策略的访问控制设计：
     * - dm_policy / group_policy：控制私聊/群聊是否开放
     * - allow_from：用户白名单
     * - deny_message：拒绝时的提示消息
     * <p>
     * Note: {@code require_mention} is honored by individual channel adapters
     * (Feishu reads the SDK's mentions field, etc.) rather than at this layer,
     * because reliable mention detection is platform-specific.
     */
    protected boolean checkAccess(ChannelMessage message) {
        boolean isDM = isDirectMessage(message);

        // 1. 检查私聊/群聊策略
        String policy = isDM
                ? getConfigString("dm_policy", "open")
                : getConfigString("group_policy", "open");
        if ("closed".equals(policy)) {
            log.info("[{}] {} blocked by {} policy=closed, sender={}",
                    getChannelType(), isDM ? "DM" : "Group", isDM ? "dm" : "group", message.getSenderId());
            sendDenyMessage(message);
            return false;
        }

        // 2. 检查 allow_from 白名单
        List<String> allowFrom = getConfigList("allow_from");
        if (!allowFrom.isEmpty()) {
            if (!allowFrom.contains(message.getSenderId())) {
                log.info("[{}] Sender {} not in allow_from list", getChannelType(), message.getSenderId());
                sendDenyMessage(message);
                return false;
            }
        }

        return true;
    }

    /**
     * 发送拒绝消息
     */
    private void sendDenyMessage(ChannelMessage message) {
        String denyMsg = getConfigString("deny_message", "抱歉，您没有使用权限");
        try {
            String target = message.getReplyToken() != null ? message.getReplyToken()
                    : (message.getChatId() != null ? message.getChatId() : message.getSenderId());
            sendMessage(target, denyMsg);
        } catch (Exception e) {
            log.error("[{}] Failed to send deny message: {}", getChannelType(), e.getMessage());
        }
    }

    // ==================== 配置解析 ====================

    /**
     * 从 configJson 解析配置
     */
    protected Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[{}] Failed to parse configJson: {}", getChannelType(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 获取配置值
     */
    protected String getConfigString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    protected String getConfigString(String key, String defaultValue) {
        String value = getConfigString(key);
        return value != null ? value : defaultValue;
    }

    protected boolean getConfigBoolean(String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    protected long getConfigLong(String key, long defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /**
     * 获取配置中的列表值
     */
    protected List<String> getConfigList(String key) {
        Object value = config.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }

    /**
     * 获取渠道实体
     */
    public ChannelEntity getChannelEntity() {
        return channelEntity;
    }
}
