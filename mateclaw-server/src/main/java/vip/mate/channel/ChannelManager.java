package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.channel.dingtalk.DingTalkChannelAdapter;
import vip.mate.channel.discord.DiscordChannelAdapter;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.qq.QQChannelAdapter;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.telegram.TelegramChannelAdapter;
import vip.mate.channel.web.WebChannelAdapter;
import vip.mate.channel.wecom.WeComChannelAdapter;
import vip.mate.channel.weixin.WeixinChannelAdapter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 渠道管理器
 * <p>
 * 实现渠道生命周期管理 + 热替换机制：
 * - 管理所有渠道适配器的生命周期（启动/停止/热替换）
 * - 维护渠道类型注册表，根据 channelType 创建对应适配器
 * - 支持动态增删渠道（通过 API 启用/禁用时自动 start/stop）
 * - 应用启动时自动加载并启动所有 enabled 渠道
 * - activeAdapters 使用 ReadWriteLock 保护，读操作并发安全，热替换使用写锁
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelManager {

    private final ChannelService channelService;
    private final ChannelMessageRouter messageRouter;
    private final ChannelSessionStore channelSessionStore;
    private final ObjectMapper objectMapper;
    private final vip.mate.tool.document.GeneratedFileCache generatedFileCache;

    /**
     * Approval notification renderer — used by WeCom adapter (PR-0
     * threading; PR-1 wired the WeCom override to render a
     * {@code button_interaction} card via this service's card builder).
     * Other adapters keep using the text path on
     * {@link AbstractChannelAdapter}, which calls
     * {@code ApprovalNotificationService.staticBuildText} so this
     * field is currently consumed only by WeCom.
     */
    private final vip.mate.channel.notification.ApprovalNotificationService approvalNotificationService;

    /**
     * WeCom interactive card dispatcher (PR-1). Drives the
     * {@code button_interaction} approval card render + the inbound
     * {@code template_card_event} routing.
     */
    private final vip.mate.channel.wecom.cards.WeComCardDispatcher weComCardDispatcher;

    /**
     * WeCom keepalive scheduler (PR-1). Refreshes the "🤔 思考中..."
     * placeholder every 20s and force-finishes after 180s so long-
     * running agent tasks don't lose their stream slot.
     */
    private final vip.mate.channel.wecom.WeComKeepaliveScheduler weComKeepaliveScheduler;

    /** 运行中的渠道适配器：channelId -> adapter */
    private final Map<Long, ChannelAdapter> activeAdapters = new HashMap<>();

    /** 插件注册的渠道适配器：pluginName -> adapter */
    private final Map<String, ChannelAdapter> pluginChannels = new ConcurrentHashMap<>();

    /** 读写锁：读操作（getAdapter 等）用读锁，写操作（start/stop/replace）用写锁 */
    private final ReadWriteLock adapterLock = new ReentrantReadWriteLock();

    /** 旧 Adapter stop() 的超时线程池 */
    private final ExecutorService stopExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "channel-stop");
        t.setDaemon(true);
        return t;
    });

    /** 旧 Adapter stop() 超时时间（秒） */
    private static final int STOP_TIMEOUT_SECONDS = 5;

    /** 支持的渠道类型 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "web", "dingtalk", "feishu", "telegram", "discord", "wecom", "qq", "weixin", "slack", "webchat"
    );

    /**
     * 应用启动完成后自动加载并启动所有已启用的渠道
     * 使用 ApplicationReadyEvent 确保数据库 schema/data 初始化完成
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Initializing ChannelManager...");
        List<ChannelEntity> channels = channelService.listEnabledChannels();
        int started = 0;
        for (ChannelEntity channel : channels) {
            try {
                startChannel(channel);
                started++;
            } catch (Exception e) {
                log.error("Failed to start channel {}: {}", channel.getName(), e.getMessage());
            }
        }
        log.info("ChannelManager initialized: {}/{} channels started", started, channels.size());
    }

    /**
     * 应用关闭时停止所有渠道
     */
    @PreDestroy
    public void destroy() {
        log.info("Shutting down ChannelManager, stopping {} active channels...", activeAdapters.size());
        stopAll();
        stopExecutor.shutdownNow();
        messageRouter.shutdown();
    }

    // ==================== 渠道生命周期管理 ====================

    /**
     * 启动指定渠道
     */
    public void startChannel(ChannelEntity channel) {
        adapterLock.writeLock().lock();
        try {
            if (activeAdapters.containsKey(channel.getId())) {
                log.info("Channel {} already running, skipping", channel.getName());
                return;
            }

            ChannelAdapter adapter = createAdapter(channel);
            adapter.start();
            activeAdapters.put(channel.getId(), adapter);
            log.info("Channel started: {} (type={}, id={})", channel.getName(), channel.getChannelType(), channel.getId());
        } finally {
            adapterLock.writeLock().unlock();
        }
    }

    /**
     * 停止指定渠道
     */
    public void stopChannel(Long channelId) {
        ChannelAdapter oldAdapter;
        adapterLock.writeLock().lock();
        try {
            oldAdapter = activeAdapters.remove(channelId);
        } finally {
            adapterLock.writeLock().unlock();
        }

        if (oldAdapter != null) {
            stopAdapterSafely(oldAdapter, "stopChannel");
        }
    }

    /**
     * 热替换渠道（配置变更后调用）
     * <p>
     * 热替换流程：
     * 1. 用新配置创建并启动新 Adapter（在锁外完成，避免长时间持锁）
     * 2. 新 Adapter 就绪后，加写锁替换 activeAdapters 中的引用
     * 3. 释放锁后，异步停止旧 Adapter（给定超时）
     * 4. 如果新 Adapter start() 失败，保留旧的不变
     *
     * @param channelId 渠道ID
     */
    public void restartChannel(Long channelId) {
        ChannelEntity channel = channelService.getChannel(channelId);

        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            // 渠道已禁用，直接停止旧的
            log.info("[hot-swap] Channel {} is disabled, stopping old adapter", channel.getName());
            stopChannel(channelId);
            return;
        }

        log.info("[hot-swap] Starting hot-swap for channel: {} (type={}, id={})",
                channel.getName(), channel.getChannelType(), channelId);

        // Step 1: 在锁外创建并启动新 Adapter
        ChannelAdapter newAdapter = createAdapter(channel);
        try {
            log.info("[hot-swap] Starting new adapter for channel: {}", channel.getName());
            newAdapter.start();
            log.info("[hot-swap] New adapter started successfully: {}", channel.getName());
        } catch (Exception e) {
            // 新 Adapter 启动失败，保留旧的不变
            log.error("[hot-swap] New adapter failed to start for channel {}, keeping old adapter: {}",
                    channel.getName(), e.getMessage(), e);
            return;
        }

        // Step 2: 加写锁，原子替换
        ChannelAdapter oldAdapter;
        adapterLock.writeLock().lock();
        try {
            oldAdapter = activeAdapters.put(channelId, newAdapter);
            log.info("[hot-swap] Adapter reference swapped for channel: {} (old={})",
                    channel.getName(), oldAdapter != null ? "present" : "none");
        } finally {
            adapterLock.writeLock().unlock();
        }

        // Step 3: 锁外异步停止旧 Adapter
        if (oldAdapter != null) {
            log.info("[hot-swap] Stopping old adapter for channel: {}", channel.getName());
            stopAdapterAsync(oldAdapter, channel.getName());
        }

        log.info("[hot-swap] Hot-swap completed for channel: {} (type={}, id={})",
                channel.getName(), channel.getChannelType(), channelId);
    }

    /**
     * 停止所有渠道
     */
    public void stopAll() {
        List<ChannelAdapter> adaptersToStop;
        adapterLock.writeLock().lock();
        try {
            adaptersToStop = new ArrayList<>(activeAdapters.values());
            activeAdapters.clear();
        } finally {
            adapterLock.writeLock().unlock();
        }

        for (ChannelAdapter adapter : adaptersToStop) {
            stopAdapterSafely(adapter, "stopAll");
        }
    }

    // ==================== 查询（读锁保护） ====================

    /**
     * 获取指定渠道的适配器
     */
    public Optional<ChannelAdapter> getAdapter(Long channelId) {
        adapterLock.readLock().lock();
        try {
            return Optional.ofNullable(activeAdapters.get(channelId));
        } finally {
            adapterLock.readLock().unlock();
        }
    }

    /**
     * 按渠道类型获取适配器（返回第一个匹配的，先查内置再查插件）
     */
    public Optional<ChannelAdapter> getAdapterByType(String channelType) {
        adapterLock.readLock().lock();
        try {
            Optional<ChannelAdapter> builtin = activeAdapters.values().stream()
                    .filter(a -> a.getChannelType().equals(channelType))
                    .findFirst();
            if (builtin.isPresent()) return builtin;
            // Fallback to plugin channels
            return pluginChannels.values().stream()
                    .filter(a -> a.getChannelType().equals(channelType))
                    .findFirst();
        } finally {
            adapterLock.readLock().unlock();
        }
    }

    /**
     * 获取所有运行中的渠道适配器（含插件渠道）
     */
    public Collection<ChannelAdapter> getActiveAdapters() {
        adapterLock.readLock().lock();
        try {
            List<ChannelAdapter> all = new ArrayList<>(activeAdapters.values());
            all.addAll(pluginChannels.values());
            return List.copyOf(all);
        } finally {
            adapterLock.readLock().unlock();
        }
    }

    /**
     * 获取渠道运行状态摘要（含连接状态和最后错误信息）
     */
    public Map<String, Object> getStatus() {
        adapterLock.readLock().lock();
        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("activeCount", activeAdapters.size());
            status.put("supportedTypes", SUPPORTED_TYPES);

            List<Map<String, Object>> channels = new ArrayList<>();
            activeAdapters.forEach((id, adapter) -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", id);
                info.put("type", adapter.getChannelType());
                info.put("name", adapter.getDisplayName());
                info.put("running", adapter.isRunning());

                // 连接状态和错误信息
                if (adapter instanceof AbstractChannelAdapter aca) {
                    info.put("connectionState", aca.getConnectionState().get().name());
                    info.put("lastError", aca.getLastError());
                    info.put("reconnectAttempts", aca.backoff.getAttempts());
                    long lastEventMs = aca.getLastEventTimeMs().get();
                    info.put("lastEventTime", lastEventMs > 0
                            ? java.time.Instant.ofEpochMilli(lastEventMs).toString() : null);
                    long silentMs = System.currentTimeMillis() - lastEventMs;
                    info.put("healthStatus", silentMs > 3600_000 ? "stale"
                            : aca.getConnectionState().get() == AbstractChannelAdapter.ConnectionState.ERROR ? "error"
                            : "healthy");
                } else {
                    info.put("connectionState", adapter.isRunning() ? "CONNECTED" : "DISCONNECTED");
                    info.put("lastError", null);
                    info.put("reconnectAttempts", 0);
                }

                channels.add(info);
            });
            status.put("channels", channels);
            return status;
        } finally {
            adapterLock.readLock().unlock();
        }
    }

    /**
     * 判断是否支持该渠道类型（含插件渠道）
     */
    public boolean isSupported(String channelType) {
        if (SUPPORTED_TYPES.contains(channelType)) return true;
        return pluginChannels.values().stream()
                .anyMatch(a -> a.getChannelType().equals(channelType));
    }

    // ==================== 主动推送 ====================

    /**
     * 通过指定渠道主动推送消息
     * <p>
     * 供 CronJob 等模块调用，实现定时消息推送。
     *
     * @param channelId 渠道配置ID
     * @param targetId  目标标识（用户ID / 群组ID / 频道ID / sessionWebhook）
     * @param content   消息内容
     * @throws IllegalStateException 渠道未启动或不支持主动推送
     */
    public void sendToChannel(Long channelId, String targetId, String content) {
        sendToChannel(channelId, targetId, content, DeliveryOptions.DEFAULTS);
    }

    /**
     * RFC-063r §2.10: preferred overload — accepts a {@link DeliveryOptions}
     * Parameter Object so cron delivery (and future callers) can pass
     * Slack {@code thread_ts}, Telegram {@code message_thread_id}, multi-bot
     * {@code accountId}, etc. without growing a 5-arg signature.
     */
    public void sendToChannel(Long channelId, String targetId, String content, DeliveryOptions options) {
        ChannelAdapter adapter = getAdapter(channelId)
                .orElseThrow(() -> new IllegalStateException("Channel not active: " + channelId));
        if (!adapter.supportsProactiveSend()) {
            throw new UnsupportedOperationException(
                    "Channel " + adapter.getDisplayName() + " (" + adapter.getChannelType() + ") does not support proactive send");
        }
        adapter.proactiveSend(targetId, content, options != null ? options : DeliveryOptions.DEFAULTS);
        log.info("Proactive message sent via channel {} to {}: {}chars",
                adapter.getDisplayName(), targetId, content.length());
    }

    /**
     * 通过 conversationId 主动推送消息（自动查找渠道和目标）
     * <p>
     * 从 ChannelSessionStore 中查找 conversationId 对应的渠道和推送目标。
     *
     * @param conversationId 会话ID（如 dingtalk:xxx）
     * @param content        消息内容
     * @throws IllegalStateException 找不到会话或渠道未启动
     */
    public void sendToConversation(String conversationId, String content) {
        var session = channelSessionStore.getSession(conversationId);
        if (session == null) {
            throw new IllegalStateException("No channel session found for conversation: " + conversationId);
        }
        sendToChannel(session.getChannelId(), session.getTargetId(), content);
    }

    // ==================== 插件渠道管理 ====================

    /**
     * Register a channel adapter from a plugin.
     *
     * @param pluginName the plugin name (used as key for unregistration)
     * @param adapter    the channel adapter
     */
    public void registerPluginChannel(String pluginName, ChannelAdapter adapter) {
        try {
            adapter.start();
            pluginChannels.put(pluginName, adapter);
            log.info("Plugin channel registered: {} (type={})", pluginName, adapter.getChannelType());
        } catch (Exception e) {
            log.error("Failed to start plugin channel {}: {}", pluginName, e.getMessage(), e);
        }
    }

    /**
     * Unregister a plugin channel.
     */
    public void unregisterPluginChannel(String pluginName) {
        ChannelAdapter adapter = pluginChannels.remove(pluginName);
        if (adapter != null) {
            stopAdapterSafely(adapter, "unregisterPluginChannel");
            log.info("Plugin channel unregistered: {}", pluginName);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 安全停止 Adapter：捕获异常，不影响调用方
     */
    private void stopAdapterSafely(ChannelAdapter adapter, String context) {
        try {
            adapter.stop();
            log.info("[{}] Adapter stopped: {} (type={})", context, adapter.getDisplayName(), adapter.getChannelType());
        } catch (Exception e) {
            log.error("[{}] Error stopping adapter {} (type={}): {}",
                    context, adapter.getDisplayName(), adapter.getChannelType(), e.getMessage(), e);
        }
    }

    /**
     * 异步停止旧 Adapter，带超时保护
     * <p>
     * 旧 Adapter 的 stop() 异常不影响新 Adapter 运行。
     */
    private void stopAdapterAsync(ChannelAdapter oldAdapter, String channelName) {
        Future<?> future = stopExecutor.submit(() -> {
            try {
                oldAdapter.stop();
                log.info("[hot-swap] Old adapter stopped: {}", channelName);
            } catch (Exception e) {
                log.error("[hot-swap] Error stopping old adapter {}: {}", channelName, e.getMessage(), e);
            }
        });

        // 超时监控（也在后台执行，不阻塞调用方）
        stopExecutor.submit(() -> {
            try {
                future.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("[hot-swap] Old adapter stop timed out after {}s: {}, cancelling",
                        STOP_TIMEOUT_SECONDS, channelName);
                future.cancel(true);
            } catch (Exception e) {
                log.error("[hot-swap] Unexpected error waiting for old adapter stop: {}", e.getMessage());
            }
        });
    }

    // ==================== 工厂方法 ====================

    /**
     * 根据渠道实体创建对应的适配器实例
     * 采用渠道注册表模式，根据类型创建对应适配器
     */
    private ChannelAdapter createAdapter(ChannelEntity channel) {
        String type = channel.getChannelType();
        return switch (type) {
            case "web" -> new WebChannelAdapter(channel, messageRouter, objectMapper);
            case "dingtalk" -> new DingTalkChannelAdapter(channel, messageRouter, objectMapper, generatedFileCache);
            case "feishu" -> new FeishuChannelAdapter(channel, messageRouter, objectMapper);
            case "telegram" -> new TelegramChannelAdapter(channel, messageRouter, objectMapper);
            case "discord" -> new DiscordChannelAdapter(channel, messageRouter, objectMapper);
            case "wecom" -> new WeComChannelAdapter(channel, messageRouter, objectMapper,
                    approvalNotificationService, weComCardDispatcher, weComKeepaliveScheduler);
            case "qq" -> new QQChannelAdapter(channel, messageRouter, objectMapper);
            case "weixin" -> new WeixinChannelAdapter(channel, messageRouter, objectMapper);
            case "slack" -> new vip.mate.channel.slack.SlackChannelAdapter(channel, messageRouter, objectMapper);
            case "webchat" -> new vip.mate.channel.webchat.WebChatChannelAdapter(channel, messageRouter, objectMapper);
            default -> throw new IllegalArgumentException("Unsupported channel type: " + type);
        };
    }
}
