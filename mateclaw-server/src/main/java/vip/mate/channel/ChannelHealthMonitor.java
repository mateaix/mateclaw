package vip.mate.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道健康监控
 * <p>
 * 每 1 分钟（RFC-024 Change 2）检查所有活跃渠道适配器：
 * - 连接状态为 ERROR 超过 5 分钟 → 触发重启
 * - 连接状态为 CONNECTED 但超过 {@link ChannelAdapter#stalenessThreshold()} 无事件 → stale 重启
 * - 每渠道每小时最多 10 次重启，cooldown 2 分钟
 * <p>
 * <b>RFC-024</b>：stale 阈值改为读 adapter 自声明（默认 60min，WeChat 类长轮询 5min），
 * 检查频率从 5min 降到 1min，让短阈值能真正生效；
 * 配合 {@code AbstractChannelAdapter.touchActivity()} 精准刷新活跃时间。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthMonitor {

    private final ChannelManager channelManager;

    /** 错误状态超过此时间触发重启（毫秒） */
    private static final long ERROR_THRESHOLD_MS = 5 * 60 * 1000;

    /**
     * 兜底 stale 阈值（当 adapter 未覆盖 {@link ChannelAdapter#stalenessThreshold()} 时使用）。
     * RFC-024 Change 2 之前是硬编码 60min；现在由 adapter 自行声明。
     */
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(60);

    /** 每渠道每小时最大重启次数 */
    private static final int MAX_RESTARTS_PER_HOUR = 10;

    /** 同一渠道两次重启最小间隔（毫秒） */
    private static final long COOLDOWN_MS = 2 * 60 * 1000;

    /** 重启历史记录（channelId → 重启时间列表） */
    private final ConcurrentHashMap<Long, List<Instant>> restartHistory = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 60_000)   // RFC-024 Change 2: 每 1 分钟（原 5 分钟）
    public void checkHealth() {
        Collection<ChannelAdapter> adapters = channelManager.getActiveAdapters();
        if (adapters.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        int checked = 0;
        int restarted = 0;

        for (ChannelAdapter adapter : adapters) {
            if (!(adapter instanceof AbstractChannelAdapter aca)) {
                continue;
            }
            checked++;

            Long channelId = aca.channelEntity.getId();
            AbstractChannelAdapter.ConnectionState state = aca.getConnectionState().get();
            long lastEvent = aca.getLastEventTimeMs().get();
            long sinceLastEvent = now - lastEvent;

            String reason = null;

            // 检查 1：ERROR 状态超过阈值
            if (state == AbstractChannelAdapter.ConnectionState.ERROR && sinceLastEvent > ERROR_THRESHOLD_MS) {
                reason = String.format("ERROR state for %ds", sinceLastEvent / 1000);
            }

            // 检查 2：CONNECTED 但长时间无事件（stale）— RFC-024 读 adapter 自声明阈值
            Duration staleThreshold;
            try {
                Duration d = adapter.stalenessThreshold();
                staleThreshold = (d == null || d.isNegative()) ? DEFAULT_STALE_THRESHOLD : d;
            } catch (Exception ex) {
                staleThreshold = DEFAULT_STALE_THRESHOLD;
            }
            if (reason == null && state == AbstractChannelAdapter.ConnectionState.CONNECTED
                    && !staleThreshold.isZero() && sinceLastEvent > staleThreshold.toMillis()) {
                reason = String.format("stale connection, no events for %ds (threshold=%ds)",
                        sinceLastEvent / 1000, staleThreshold.toSeconds());
            }

            if (reason != null) {
                if (canRestart(channelId, now)) {
                    log.warn("[ChannelHealth] Restarting channel {} ({}): {}",
                            channelId, aca.getDisplayName(), reason);
                    try {
                        channelManager.restartChannel(channelId);
                        recordRestart(channelId, now);
                        restarted++;
                    } catch (Exception e) {
                        log.error("[ChannelHealth] Failed to restart channel {}: {}",
                                channelId, e.getMessage());
                    }
                } else {
                    log.warn("[ChannelHealth] Channel {} ({}) unhealthy ({}), but restart rate-limited",
                            channelId, aca.getDisplayName(), reason);
                }
            }
        }

        if (restarted > 0) {
            log.info("[ChannelHealth] Check completed: {}/{} channels checked, {} restarted",
                    checked, adapters.size(), restarted);
        }
    }

    /**
     * 检查是否允许重启（限流 + cooldown）
     */
    private boolean canRestart(Long channelId, long nowMs) {
        List<Instant> history = restartHistory.computeIfAbsent(channelId, k -> new ArrayList<>());

        // 清理 1 小时前的记录
        Instant oneHourAgo = Instant.ofEpochMilli(nowMs - 3600_000);
        history.removeIf(t -> t.isBefore(oneHourAgo));

        // 限流检查
        if (history.size() >= MAX_RESTARTS_PER_HOUR) {
            return false;
        }

        // cooldown 检查
        if (!history.isEmpty()) {
            Instant lastRestart = history.get(history.size() - 1);
            if (nowMs - lastRestart.toEpochMilli() < COOLDOWN_MS) {
                return false;
            }
        }

        return true;
    }

    private void recordRestart(Long channelId, long nowMs) {
        restartHistory.computeIfAbsent(channelId, k -> new ArrayList<>())
                .add(Instant.ofEpochMilli(nowMs));
    }
}
