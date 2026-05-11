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
import vip.mate.channel.leader.ChannelLeaderElection;
import vip.mate.channel.leader.LeaderLease;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.qq.QQChannelAdapter;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.telegram.TelegramChannelAdapter;
import vip.mate.channel.web.WebChannelAdapter;
import vip.mate.channel.wecom.WeComChannelAdapter;
import vip.mate.channel.weixin.WeixinChannelAdapter;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
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

    /**
     * Distributed leader election. Channels whose adapter reports
     * {@link ChannelAdapter#requiresSingleLeader()} are gated on a lease so
     * only one node opens the upstream WebSocket / long-poll at a time.
     */
    private final ChannelLeaderElection leaderElection;

    /** 运行中的渠道适配器：channelId -> adapter */
    private final Map<Long, ChannelAdapter> activeAdapters = new HashMap<>();

    /** 插件注册的渠道适配器：pluginName -> adapter */
    private final Map<String, ChannelAdapter> pluginChannels = new ConcurrentHashMap<>();

    /** Held leadership leases for plugin channels: pluginName -> lease */
    private final Map<String, LeaderLease> pluginLeases = new ConcurrentHashMap<>();

    /** Lease-extension futures for plugin channels: pluginName -> heartbeat */
    private final Map<String, ScheduledFuture<?>> pluginHeartbeatFutures = new ConcurrentHashMap<>();

    /**
     * Serializes register / unregister / shutdown / heartbeat-loss cleanup
     * for plugin channels. The three plugin maps each use
     * {@code ConcurrentHashMap}, which makes individual put/remove atomic
     * — but not the multi-map sequences these paths run (snapshot, clear,
     * stop adapter, release lease). Without this lock, a concurrent
     * {@code registerPluginChannel} could insert into {@code pluginChannels}
     * after {@code stopAll} snapshot-copies it but before
     * {@code pluginChannels.clear()}, leaking an unstopped adapter and a
     * never-released lease.
     */
    private final Object pluginLifecycleLock = new Object();

    /** Held leadership leases for leader-required channels: channelId -> lease */
    private final Map<Long, LeaderLease> activeLeases = new HashMap<>();

    /** Lease-extension futures: channelId -> heartbeat */
    private final Map<Long, ScheduledFuture<?>> heartbeatFutures = new HashMap<>();

    /** Follower retry futures: channelId -> retry */
    private final Map<Long, ScheduledFuture<?>> followerRetryFutures = new HashMap<>();

    /**
     * Reconcile futures for <b>non</b>-leader-required active adapters
     * (e.g. webhook-mode Feishu, polling-disabled Telegram). Without these,
     * cross-node admin actions on a follower would never reach the running
     * adapter on another node — only leaders run reconciliation through
     * their heartbeat, so a webhook-running node would otherwise be deaf
     * to disable / delete / config / mode-flip changes processed elsewhere.
     */
    private final Map<Long, ScheduledFuture<?>> reconcileFutures = new HashMap<>();

    /**
     * Last {@code update_time} the leader observed for each owned channel.
     * Compared against the DB row on every heartbeat — a newer value means
     * another node has applied a config change, and we (the connection
     * holder) need to apply it locally too. Otherwise the leader keeps
     * running with stale credentials/mode after admin edits.
     */
    private final Map<Long, LocalDateTime> lastSeenChannelUpdateTime = new HashMap<>();

    /** 读写锁：读操作（getAdapter 等）用读锁，写操作（start/stop/replace）用写锁 */
    private final ReadWriteLock adapterLock = new ReentrantReadWriteLock();

    /** 旧 Adapter stop() 的超时线程池 */
    private final ExecutorService stopExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "channel-stop");
        t.setDaemon(true);
        return t;
    });

    /**
     * Heartbeat + follower-retry scheduler. A small pool is enough — both
     * tasks are short-lived (lock extend or a single startChannel attempt).
     */
    private final ScheduledExecutorService leaderScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "channel-leader");
        t.setDaemon(true);
        return t;
    });

    /** 旧 Adapter stop() 超时时间（秒） */
    private static final int STOP_TIMEOUT_SECONDS = 5;

    /**
     * Lease heartbeat cadence. Must be well under
     * {@link ChannelLeaderElection#LOCK_AT_MOST_FOR} (60s) so a single
     * missed tick doesn't lose leadership; 20s gives us three chances per
     * window.
     */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 20L;

    /**
     * How often a follower retries to acquire leadership. Picked so a
     * leader dying gets failed-over within (lease window + this interval)
     * = ~90s in the worst case, without hammering the DB.
     */
    private static final long FOLLOWER_RETRY_INTERVAL_SECONDS = 30L;

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
        leaderScheduler.shutdownNow();
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
            if (adapter.requiresSingleLeader()) {
                attemptLeaderStart(channel, adapter);
            } else {
                adapter.start();
                activeAdapters.put(channel.getId(), adapter);
                lastSeenChannelUpdateTime.put(channel.getId(), channel.getUpdateTime());
                // A follower retry may still be scheduled if this channel was
                // previously in leader-required mode and just flipped to a
                // non-leader transport (e.g. Feishu websocket → webhook).
                // Cancel it so we don't tick forever on a no-op startChannel.
                cancelFollowerRetryLocked(channel.getId());
                // Schedule cross-node reconciliation for this non-leader adapter:
                // followers driven by their retry tick re-read the channel, but
                // a running non-leader has neither a heartbeat nor a retry, so
                // without this ticker it would never notice admin actions
                // processed on a different node.
                scheduleReconcileLocked(channel.getId(), channel.getName());
                log.info("Channel started: {} (type={}, id={})", channel.getName(), channel.getChannelType(), channel.getId());
            }
        } finally {
            adapterLock.writeLock().unlock();
        }
    }

    /**
     * 停止指定渠道
     */
    public void stopChannel(Long channelId) {
        ChannelAdapter oldAdapter;
        LeaderLease lease;
        adapterLock.writeLock().lock();
        try {
            oldAdapter = activeAdapters.remove(channelId);
            lease = activeLeases.remove(channelId);
            lastSeenChannelUpdateTime.remove(channelId);
            cancelHeartbeatLocked(channelId);
            cancelFollowerRetryLocked(channelId);
            cancelReconcileLocked(channelId);
        } finally {
            adapterLock.writeLock().unlock();
        }

        if (oldAdapter != null) {
            stopAdapterSafely(oldAdapter, "stopChannel");
        }
        if (lease != null) {
            try {
                lease.release();
                log.info("[leader] Released lease for channel id={}", channelId);
            } catch (Exception e) {
                log.warn("[leader] Failed to release lease for channel id={}: {}", channelId, e.getMessage());
            }
        }
    }

    // ==================== Leader election ====================

    /**
     * Try to become leader for {@code channel} and start its adapter on
     * success. On failure (another node holds the lease) schedule a
     * follower retry so we'll take over when the current leader dies.
     *
     * <p>Caller must hold the adapter write lock.
     */
    private void attemptLeaderStart(ChannelEntity channel, ChannelAdapter adapter) {
        String key = channel.getChannelType() + ":" + channel.getId();
        Optional<LeaderLease> maybeLease = leaderElection.tryAcquire(key);
        if (maybeLease.isEmpty()) {
            log.info("[leader] Channel {} (id={}, type={}) is owned by another node — entering follower mode",
                    channel.getName(), channel.getId(), channel.getChannelType());
            scheduleFollowerRetryLocked(channel.getId());
            return;
        }

        LeaderLease lease = maybeLease.get();
        try {
            adapter.start();
            activeAdapters.put(channel.getId(), adapter);
            activeLeases.put(channel.getId(), lease);
            lastSeenChannelUpdateTime.put(channel.getId(), channel.getUpdateTime());
            scheduleHeartbeatLocked(channel.getId(), channel.getName());
            cancelFollowerRetryLocked(channel.getId());
            log.info("[leader] Channel started as leader: {} (type={}, id={})",
                    channel.getName(), channel.getChannelType(), channel.getId());
        } catch (Exception e) {
            log.error("[leader] Adapter start failed after acquiring lease for channel {}: {} — releasing lease",
                    channel.getName(), e.getMessage(), e);
            lease.release();
            throw e instanceof RuntimeException re ? re
                    : new RuntimeException("Channel start failed: " + e.getMessage(), e);
        }
    }

    /**
     * Schedule periodic heartbeat to extend the lease. Caller must hold
     * the adapter write lock.
     */
    private void scheduleHeartbeatLocked(Long channelId, String channelName) {
        cancelHeartbeatLocked(channelId);
        ScheduledFuture<?> f = leaderScheduler.scheduleAtFixedRate(
                () -> heartbeat(channelId, channelName),
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        heartbeatFutures.put(channelId, f);
    }

    /**
     * One heartbeat tick. Extends the lease, then reconciles the local
     * adapter against the current DB row — the leader is the only node
     * holding the upstream connection, so it's the only one that can
     * apply admin actions (disable / config change / delete) issued on
     * a different node. Without this, e.g. a {@code /toggle?enabled=false}
     * processed by a follower would never reach the actual connection.
     */
    private void heartbeat(Long channelId, String channelName) {
        LeaderLease lease;
        adapterLock.readLock().lock();
        try {
            lease = activeLeases.get(channelId);
        } finally {
            adapterLock.readLock().unlock();
        }
        if (lease == null) {
            return;
        }
        boolean stillOurs = lease.extend(ChannelLeaderElection.LOCK_AT_MOST_FOR);
        if (!stillOurs) {
            log.warn("[leader] Lost leadership for channel {} (id={}) — stopping local adapter and re-entering election",
                    channelName, channelId);
            handleLeadershipLoss(channelId);
            return;
        }
        reconcileChannel(channelId, channelName);
    }

    /**
     * Re-read the channel from the DB and apply any admin-side changes
     * (disable, delete, config update) the leader hasn't seen yet because
     * the API call was processed by a different node.
     *
     * <p>Package-private for unit testing — callers should rely on the
     * heartbeat scheduler invoking this on its tick.
     */
    void reconcileChannel(Long channelId, String channelName) {
        ChannelEntity current;
        try {
            current = channelService.getChannel(channelId);
        } catch (MateClawException e) {
            if (e.getMsgKey() != null && e.getMsgKey().startsWith("err.channel.not_found")) {
                log.info("[reconcile] Channel id={} no longer exists — stopping local adapter and releasing lease",
                        channelId);
                stopChannel(channelId);
            } else {
                log.debug("[reconcile] Channel lookup failed for id={}: {}", channelId, e.getMessage());
            }
            return;
        } catch (Exception e) {
            // Transient DB issue; skip this tick and try again on the next heartbeat.
            log.debug("[reconcile] Channel lookup failed for id={}: {}", channelId, e.getMessage());
            return;
        }

        if (!Boolean.TRUE.equals(current.getEnabled())) {
            log.info("[reconcile] Channel {} (id={}) is now disabled — stopping local adapter",
                    channelName, channelId);
            stopChannel(channelId);
            return;
        }

        LocalDateTime previousSeen;
        adapterLock.readLock().lock();
        try {
            previousSeen = lastSeenChannelUpdateTime.get(channelId);
        } finally {
            adapterLock.readLock().unlock();
        }
        LocalDateTime currentUpdateTime = current.getUpdateTime();
        if (currentUpdateTime != null && previousSeen != null
                && currentUpdateTime.isAfter(previousSeen)) {
            log.info("[reconcile] Channel {} (id={}) config changed ({} → {})",
                    channelName, channelId, previousSeen, currentUpdateTime);
            applyConfigChange(channelId, current);
        }
    }

    /**
     * Apply a detected config change to a locally-running channel.
     *
     * <p>The fast path is the in-place swap that preserves the lease —
     * but it is only valid when we are the current leader (we already
     * hold {@code activeLeases[channelId]}). Without that gate, a
     * non-leader node observing a {@code webhook → websocket} flip
     * would call {@code newAdapter.start()} directly inside the swap
     * and open a duplicate upstream connection, defeating the leader
     * election. Every other transition — including
     * {@code non-leader → leader-required}, {@code leader-required →
     * non-leader}, and plain non-leader config updates — must go
     * through {@code stopChannel} + {@code startChannel} so the lease
     * is correctly released or acquired and follower retry is
     * scheduled when election is lost.
     *
     * <p>Package-private for unit testing — see {@link #reconcileChannel}.
     */
    void applyConfigChange(Long channelId, ChannelEntity newChannel) {
        ChannelAdapter probe = createAdapter(newChannel);
        boolean newRequiresLeader = probe.requiresSingleLeader();
        boolean weHaveLease;
        adapterLock.readLock().lock();
        try {
            weHaveLease = activeLeases.containsKey(channelId);
        } finally {
            adapterLock.readLock().unlock();
        }

        if (newRequiresLeader && weHaveLease) {
            // Case A: same leader-required mode and we are the current leader
            // — preserve the lease across the adapter swap.
            swapAdapterPreservingLease(channelId, newChannel);
            return;
        }

        // All other cases: tear down local state and route through
        // startChannel so leader election runs, lease is released, or both.
        log.info("[reconcile] Channel {} (id={}) config change (newRequiresLeader={}, weHaveLease={}) — stop+start",
                newChannel.getName(), channelId, newRequiresLeader, weHaveLease);
        stopChannel(channelId);
        try {
            startChannel(newChannel);
        } catch (Exception e) {
            log.error("[reconcile] Restart after config change failed for channel {} (id={}): {}",
                    newChannel.getName(), channelId, e.getMessage(), e);
        }
    }

    /**
     * Swap to a freshly-built adapter using the new config, while keeping
     * the leadership lease and heartbeat in place. The lease is only
     * released if the new adapter fails to start, in which case we fall
     * back to follower mode so another node can try.
     */
    private void swapAdapterPreservingLease(Long channelId, ChannelEntity newChannel) {
        ChannelAdapter oldAdapter;
        adapterLock.writeLock().lock();
        try {
            oldAdapter = activeAdapters.remove(channelId);
        } finally {
            adapterLock.writeLock().unlock();
        }
        if (oldAdapter != null) {
            stopAdapterSafely(oldAdapter, "reconcile-swap");
        }

        ChannelAdapter newAdapter = createAdapter(newChannel);
        boolean started = false;
        Exception startError = null;
        try {
            newAdapter.start();
            started = true;
        } catch (Exception e) {
            startError = e;
        }

        LeaderLease leaseToRelease = null;
        adapterLock.writeLock().lock();
        try {
            if (started) {
                activeAdapters.put(channelId, newAdapter);
                lastSeenChannelUpdateTime.put(channelId, newChannel.getUpdateTime());
            } else {
                leaseToRelease = activeLeases.remove(channelId);
                lastSeenChannelUpdateTime.remove(channelId);
                cancelHeartbeatLocked(channelId);
                scheduleFollowerRetryLocked(channelId);
            }
        } finally {
            adapterLock.writeLock().unlock();
        }

        if (!started) {
            log.error("[reconcile] New adapter start failed for channel {} (id={}): {} — released lease, entering follower mode",
                    newChannel.getName(), channelId,
                    startError != null ? startError.getMessage() : "unknown");
            if (leaseToRelease != null) {
                leaseToRelease.release();
            }
        }
    }

    /**
     * Drop the local adapter (without releasing the already-lost lease)
     * and start follower retry so we'll attempt to reclaim leadership
     * once the current owner stops renewing.
     */
    private void handleLeadershipLoss(Long channelId) {
        ChannelAdapter local;
        adapterLock.writeLock().lock();
        try {
            local = activeAdapters.remove(channelId);
            activeLeases.remove(channelId); // already lost; do not call release()
            cancelHeartbeatLocked(channelId);
            scheduleFollowerRetryLocked(channelId);
        } finally {
            adapterLock.writeLock().unlock();
        }
        if (local != null) {
            stopAdapterSafely(local, "leadership-loss");
        }
    }

    /**
     * Schedule periodic follower retry. Caller must hold the adapter
     * write lock.
     */
    private void scheduleFollowerRetryLocked(Long channelId) {
        if (followerRetryFutures.containsKey(channelId)) {
            return;
        }
        ScheduledFuture<?> f = leaderScheduler.scheduleAtFixedRate(
                () -> followerRetry(channelId),
                FOLLOWER_RETRY_INTERVAL_SECONDS, FOLLOWER_RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
        followerRetryFutures.put(channelId, f);
    }

    /**
     * One follower-retry tick. Re-reads the channel from the DB (it may
     * have been disabled or deleted) and attempts to start it. The retry
     * cancels itself once we successfully become leader.
     */
    /** Package-private for unit testing — see {@link #reconcileChannel}. */
    void followerRetry(Long channelId) {
        ChannelEntity current;
        try {
            current = channelService.getChannel(channelId);
        } catch (MateClawException e) {
            // Channel was deleted on another node — cancel the retry so we
            // don't leak a scheduled task forever. Other exception codes
            // (e.g. transient DB errors) fall through to the generic catch
            // and let the retry continue.
            if (e.getMsgKey() != null && e.getMsgKey().startsWith("err.channel.not_found")) {
                log.info("[leader] Follower retry: channel id={} no longer exists — cancelling retry", channelId);
                adapterLock.writeLock().lock();
                try {
                    cancelFollowerRetryLocked(channelId);
                } finally {
                    adapterLock.writeLock().unlock();
                }
                return;
            }
            log.debug("[leader] Follower retry: lookup failed for channel id={}: {}", channelId, e.getMessage());
            return;
        } catch (Exception e) {
            log.debug("[leader] Follower retry: lookup failed for channel id={}: {}", channelId, e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(current.getEnabled())) {
            adapterLock.writeLock().lock();
            try {
                cancelFollowerRetryLocked(channelId);
            } finally {
                adapterLock.writeLock().unlock();
            }
            return;
        }
        try {
            startChannel(current);
        } catch (Exception e) {
            log.debug("[leader] Follower retry: startChannel failed for id={}: {}", channelId, e.getMessage());
        }
    }

    /** Package-private for unit testing. */
    boolean hasFollowerRetry(Long channelId) {
        adapterLock.readLock().lock();
        try {
            return followerRetryFutures.containsKey(channelId);
        } finally {
            adapterLock.readLock().unlock();
        }
    }

    private void cancelHeartbeatLocked(Long channelId) {
        ScheduledFuture<?> f = heartbeatFutures.remove(channelId);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void cancelFollowerRetryLocked(Long channelId) {
        ScheduledFuture<?> f = followerRetryFutures.remove(channelId);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * Schedule the cross-node reconcile ticker for a non-leader active
     * adapter. Caller must hold the adapter write lock.
     */
    private void scheduleReconcileLocked(Long channelId, String channelName) {
        cancelReconcileLocked(channelId);
        ScheduledFuture<?> f = leaderScheduler.scheduleAtFixedRate(
                () -> reconcileChannel(channelId, channelName),
                FOLLOWER_RETRY_INTERVAL_SECONDS, FOLLOWER_RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
        reconcileFutures.put(channelId, f);
    }

    private void cancelReconcileLocked(Long channelId) {
        ScheduledFuture<?> f = reconcileFutures.remove(channelId);
        if (f != null) {
            f.cancel(false);
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

        // Leader-required channels: if we don't already own the local adapter
        // (i.e. we are a follower, or this is a brand-new channel), there is
        // nothing to hot-swap. Fall through to startChannel which handles
        // lease acquisition and follower retry. Hot-swap (which briefly opens
        // a second upstream connection) is also avoided here so we don't
        // double-occupy the bot's connection quota during a restart.
        boolean weHaveAdapter;
        boolean weHaveLease;
        adapterLock.readLock().lock();
        try {
            weHaveAdapter = activeAdapters.containsKey(channelId);
            weHaveLease = activeLeases.containsKey(channelId);
        } finally {
            adapterLock.readLock().unlock();
        }
        ChannelAdapter probe = createAdapter(channel);
        if (probe.requiresSingleLeader()) {
            log.info("[hot-swap] Channel {} requires single-leader; stop+start instead of hot-swap (weHaveAdapter={}, weHaveLease={})",
                    channel.getName(), weHaveAdapter, weHaveLease);
            stopChannel(channelId);
            startChannel(channel);
            return;
        }
        // Mode flip: we currently hold a lease but the new config is no
        // longer leader-required (e.g. Feishu WS → webhook). The lease,
        // heartbeat, and lastSeenUpdateTime must all be torn down before
        // the new non-leader adapter starts — the in-place hot-swap path
        // below would leave them behind until the next heartbeat tick
        // noticed and re-restarted, causing a redundant restart and a
        // window where this node is silently holding a lease nobody else
        // can grab. Stop+start handles all the cleanup in one shot.
        if (weHaveLease) {
            log.info("[hot-swap] Channel {} flipping leader-required → non-leader; stop+start to release lease",
                    channel.getName());
            stopChannel(channelId);
            startChannel(channel);
            return;
        }
        if (!weHaveAdapter) {
            log.info("[hot-swap] No local adapter for channel {}, delegating to startChannel", channel.getName());
            startChannel(channel);
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
            // Mark the version we've now applied so the reconcile ticker
            // (running for non-leader adapters) doesn't immediately fire a
            // redundant swap on its next tick.
            lastSeenChannelUpdateTime.put(channelId, channel.getUpdateTime());
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
        List<LeaderLease> leasesToRelease;
        List<ChannelAdapter> pluginAdaptersToStop;
        List<LeaderLease> pluginLeasesToRelease;
        adapterLock.writeLock().lock();
        try {
            adaptersToStop = new ArrayList<>(activeAdapters.values());
            leasesToRelease = new ArrayList<>(activeLeases.values());
            activeAdapters.clear();
            activeLeases.clear();
            lastSeenChannelUpdateTime.clear();
            heartbeatFutures.values().forEach(f -> f.cancel(false));
            heartbeatFutures.clear();
            followerRetryFutures.values().forEach(f -> f.cancel(false));
            followerRetryFutures.clear();
            reconcileFutures.values().forEach(f -> f.cancel(false));
            reconcileFutures.clear();
        } finally {
            adapterLock.writeLock().unlock();
        }

        // Plugin channels live on a different map (keyed by pluginName), but
        // shutdown must release their leases + cancel their heartbeats just
        // like DB-backed ones. Without this, a plugin-supplied single-leader
        // adapter would skip graceful release on @PreDestroy and its lease
        // would stay locked until the lockAtMostFor window expired.
        //
        // Holding pluginLifecycleLock makes the snapshot+clear atomic
        // against concurrent register / unregister / heartbeat-loss
        // cleanup, so we don't leak an adapter that was registered after
        // the snapshot but before the clear.
        synchronized (pluginLifecycleLock) {
            pluginAdaptersToStop = new ArrayList<>(pluginChannels.values());
            pluginChannels.clear();
            pluginLeasesToRelease = new ArrayList<>(pluginLeases.values());
            pluginLeases.clear();
            pluginHeartbeatFutures.values().forEach(f -> f.cancel(false));
            pluginHeartbeatFutures.clear();
        }

        for (ChannelAdapter adapter : adaptersToStop) {
            stopAdapterSafely(adapter, "stopAll");
        }
        for (ChannelAdapter adapter : pluginAdaptersToStop) {
            stopAdapterSafely(adapter, "stopAll-plugin");
        }
        for (LeaderLease lease : leasesToRelease) {
            try {
                lease.release();
            } catch (Exception e) {
                log.warn("[leader] stopAll: failed to release lease '{}': {}", lease.getName(), e.getMessage());
            }
        }
        for (LeaderLease lease : pluginLeasesToRelease) {
            try {
                lease.release();
            } catch (Exception e) {
                log.warn("[leader] stopAll: failed to release plugin lease '{}': {}", lease.getName(), e.getMessage());
            }
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
     * <p>If the adapter reports {@link ChannelAdapter#requiresSingleLeader()},
     * the framework gates the local register on a distributed lease keyed by
     * {@code plugin:{pluginName}}. When another node already owns the lease
     * this node skips registration (its plugin instance is loaded but inert
     * locally) — see the scope note on {@link ChannelAdapter#requiresSingleLeader()}.
     *
     * @param pluginName the plugin name (used as key for unregistration)
     * @param adapter    the channel adapter
     */
    public void registerPluginChannel(String pluginName, ChannelAdapter adapter) {
        synchronized (pluginLifecycleLock) {
            LeaderLease lease = null;
            if (adapter.requiresSingleLeader()) {
                Optional<LeaderLease> maybeLease = leaderElection.tryAcquire("plugin:" + pluginName);
                if (maybeLease.isEmpty()) {
                    log.info("[leader] Plugin channel {} (type={}) is owned by another node — skipping local registration",
                            pluginName, adapter.getChannelType());
                    return;
                }
                lease = maybeLease.get();
            }

            try {
                adapter.start();
                pluginChannels.put(pluginName, adapter);
                if (lease != null) {
                    pluginLeases.put(pluginName, lease);
                    schedulePluginHeartbeatLocked(pluginName);
                }
                log.info("Plugin channel registered: {} (type={})", pluginName, adapter.getChannelType());
            } catch (Exception e) {
                log.error("Failed to start plugin channel {}: {}", pluginName, e.getMessage(), e);
                if (lease != null) {
                    lease.release();
                }
            }
        }
    }

    /**
     * Unregister a plugin channel.
     */
    public void unregisterPluginChannel(String pluginName) {
        ChannelAdapter adapter;
        ScheduledFuture<?> heartbeat;
        LeaderLease lease;
        synchronized (pluginLifecycleLock) {
            adapter = pluginChannels.remove(pluginName);
            heartbeat = pluginHeartbeatFutures.remove(pluginName);
            lease = pluginLeases.remove(pluginName);
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (adapter != null) {
            stopAdapterSafely(adapter, "unregisterPluginChannel");
            log.info("Plugin channel unregistered: {}", pluginName);
        }
        if (lease != null) {
            try {
                lease.release();
            } catch (Exception e) {
                log.warn("[leader] Failed to release plugin lease '{}': {}", pluginName, e.getMessage());
            }
        }
    }

    /** Caller must hold {@link #pluginLifecycleLock}. */
    private void schedulePluginHeartbeatLocked(String pluginName) {
        ScheduledFuture<?> existing = pluginHeartbeatFutures.remove(pluginName);
        if (existing != null) {
            existing.cancel(false);
        }
        ScheduledFuture<?> f = leaderScheduler.scheduleAtFixedRate(
                () -> pluginHeartbeatTick(pluginName),
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        pluginHeartbeatFutures.put(pluginName, f);
    }

    /**
     * One plugin heartbeat tick. The tick runs on the scheduler thread, so
     * it can race with {@code registerPluginChannel} / {@code unregisterPluginChannel}
     * / {@code stopAll}. Serializing the loss-handler on
     * {@link #pluginLifecycleLock} keeps the three maps consistent (no
     * "stop ran twice" or "lease released after register re-acquired").
     */
    private void pluginHeartbeatTick(String pluginName) {
        LeaderLease lease = pluginLeases.get(pluginName);
        if (lease == null) {
            return;
        }
        boolean stillOurs = lease.extend(ChannelLeaderElection.LOCK_AT_MOST_FOR);
        if (stillOurs) {
            return;
        }
        ChannelAdapter local;
        ScheduledFuture<?> self;
        synchronized (pluginLifecycleLock) {
            // Re-check under the lock — a concurrent unregister may have
            // already torn everything down between the failed extend and
            // our entering the locked region.
            LeaderLease currentLease = pluginLeases.remove(pluginName);
            if (currentLease == null) {
                return;
            }
            local = pluginChannels.remove(pluginName);
            self = pluginHeartbeatFutures.remove(pluginName);
        }
        log.warn("[leader] Lost plugin lease '{}' — unregistering local adapter", pluginName);
        if (self != null) {
            self.cancel(false);
        }
        if (local != null) {
            stopAdapterSafely(local, "plugin-leadership-loss");
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
     *
     * <p>Package-private + non-final so unit tests can substitute a stub
     * adapter without spinning up real WebSocket / HTTP clients.
     */
    ChannelAdapter createAdapter(ChannelEntity channel) {
        String type = channel.getChannelType();
        return switch (type) {
            case "web" -> new WebChannelAdapter(channel, messageRouter, objectMapper);
            case "dingtalk" -> new DingTalkChannelAdapter(channel, messageRouter, objectMapper, generatedFileCache);
            case "feishu" -> new FeishuChannelAdapter(channel, messageRouter, objectMapper);
            case "telegram" -> new TelegramChannelAdapter(channel, messageRouter, objectMapper);
            case "discord" -> new DiscordChannelAdapter(channel, messageRouter, objectMapper);
            case "wecom" -> new WeComChannelAdapter(channel, messageRouter, objectMapper,
                    approvalNotificationService, weComCardDispatcher, weComKeepaliveScheduler,
                    generatedFileCache);
            case "qq" -> new QQChannelAdapter(channel, messageRouter, objectMapper);
            case "weixin" -> new WeixinChannelAdapter(channel, messageRouter, objectMapper);
            case "slack" -> new vip.mate.channel.slack.SlackChannelAdapter(channel, messageRouter, objectMapper);
            case "webchat" -> new vip.mate.channel.webchat.WebChatChannelAdapter(channel, messageRouter, objectMapper);
            default -> throw new IllegalArgumentException("Unsupported channel type: " + type);
        };
    }
}
