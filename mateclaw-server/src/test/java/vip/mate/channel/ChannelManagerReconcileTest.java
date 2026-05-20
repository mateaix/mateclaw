package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.channel.leader.ChannelLeaderElection;
import vip.mate.channel.leader.LeaderLease;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Behavioural tests for the multi-instance reconciliation paths:
 * heartbeat-driven detection of disabled / deleted / config-changed
 * channels, and follower-retry cancellation on channel deletion.
 *
 * <p>These exercise the fixes that prevent a leader node from running
 * stale config (or a deleted channel) just because the admin API call
 * happened to land on a different node.
 */
class ChannelManagerReconcileTest {

    private ChannelService channelService;
    private ChannelLeaderElection election;
    private ChannelManager manager;
    private TrackingAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        channelService = mock(ChannelService.class);
        election = mock(ChannelLeaderElection.class);
        manager = new ChannelManager(
                channelService,
                mock(ChannelMessageRouter.class),
                mock(ChannelSessionStore.class),
                new ObjectMapper(),
                mock(vip.mate.tool.document.GeneratedFileCache.class),
                mock(vip.mate.channel.notification.ApprovalNotificationService.class),
                mock(vip.mate.channel.wecom.cards.WeComCardDispatcher.class),
                mock(vip.mate.channel.wecom.WeComKeepaliveScheduler.class),
                mock(vip.mate.channel.feishu.FeishuMediaUploader.class),
                mock(vip.mate.channel.media.GeneratedFileScrubber.class),
                mock(vip.mate.channel.feishu.FeishuStreamingCardManager.class),
                mock(vip.mate.channel.feishu.cards.FeishuCardDispatcher.class),
                mock(vip.mate.channel.feishu.FeishuClientFactory.class),
                mock(vip.mate.stt.SttService.class),
                election);
        adapter = new TrackingAdapter();
    }

    @AfterEach
    void tearDown() {
        // Shut down the leaderScheduler so test threads don't leak.
        manager.destroy();
    }

    @Test
    @DisplayName("heartbeat reconcile: disabled channel triggers local stop")
    void reconcileStopsOnDisabled() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(42L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        ChannelEntity disabled = entity(42L, "feishu");
        disabled.setEnabled(false);
        disabled.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.getChannel(42L)).thenReturn(disabled);

        manager.reconcileChannel(42L, "test-channel");

        assertFalse(manager.getAdapter(42L).isPresent(),
                "Disabled channel detected via reconciliation must stop local adapter");
        assertTrue(adapter.stopped.get(), "Adapter stop() must be invoked");
        verify(lease, times(1)).release();
    }

    @Test
    @DisplayName("heartbeat reconcile: not-found exception triggers local stop and lease release")
    void reconcileStopsOnNotFound() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(43L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        when(channelService.getChannel(43L))
                .thenThrow(new MateClawException("err.channel.not_found", "渠道不存在: 43"));

        manager.reconcileChannel(43L, "test-channel");

        assertFalse(manager.getAdapter(43L).isPresent(),
                "Deleted channel detected via reconciliation must stop local adapter");
        assertTrue(adapter.stopped.get());
        verify(lease, times(1)).release();
    }

    @Test
    @DisplayName("heartbeat reconcile: transient DB error keeps adapter running (no false-positive stop)")
    void reconcileKeepsRunningOnTransientFailure() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(44L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        when(channelService.getChannel(44L)).thenThrow(new RuntimeException("connection refused"));

        manager.reconcileChannel(44L, "test-channel");

        assertTrue(manager.getAdapter(44L).isPresent(),
                "Transient lookup errors must not stop the local adapter");
        assertFalse(adapter.stopped.get());
        verify(lease, never()).release();
    }

    @Test
    @DisplayName("config change to non-leader-required mode releases the lease (e.g. Feishu WS → webhook)")
    void modeFlipOutOfLeaderRequiredReleasesLease() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(50L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        // Stub createAdapter so that the swap doesn't need a real
        // network-backed Feishu/Telegram start(). The fresh adapter
        // reports requiresSingleLeader=false, simulating a mode flip.
        TrackingAdapter newAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        ChannelManager spied = spy(manager);
        doReturn(newAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        ChannelEntity updated = entity(50L, "feishu");
        updated.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));

        spied.applyConfigChange(50L, updated);

        verify(lease, times(1)).release();
        // The new (non-leader) adapter is started locally on this node.
        assertTrue(spied.getAdapter(50L).isPresent(),
                "After mode flip, the local node continues running the channel as a non-leader");
        assertTrue(newAdapter.started.get(), "New adapter must be started after flip");
        assertTrue(adapter.stopped.get(), "Old adapter must be stopped before swap");
    }

    @Test
    @DisplayName("config change within leader-required mode preserves the lease (in-place swap)")
    void inPlaceSwapPreservesLease() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(51L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        TrackingAdapter newAdapter = new TrackingAdapter(); // requiresSingleLeader=true
        ChannelManager spied = spy(manager);
        doReturn(newAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        ChannelEntity updated = entity(51L, "feishu");
        updated.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));

        spied.applyConfigChange(51L, updated);

        verify(lease, never()).release();
        assertTrue(spied.getAdapter(51L).isPresent());
        assertTrue(newAdapter.started.get());
        assertTrue(adapter.stopped.get());
    }

    @Test
    @DisplayName("follower retry: not-found cancels the scheduled retry (no leak)")
    void followerRetryCancelsOnNotFound() {
        // Seed a follower retry future so we can verify cancellation.
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        Map<Long, ScheduledFuture<?>> followerRetries =
                (Map<Long, ScheduledFuture<?>>) ReflectionTestUtils.getField(manager, "followerRetryFutures");
        assertNotNull(followerRetries);
        followerRetries.put(99L, future);

        when(channelService.getChannel(99L))
                .thenThrow(new MateClawException("err.channel.not_found", "渠道不存在: 99"));

        manager.followerRetry(99L);

        assertFalse(manager.hasFollowerRetry(99L),
                "Deleted channel must cancel the follower retry future");
        verify(future, times(1)).cancel(false);
    }

    @Test
    @DisplayName("follower retry: transient DB error keeps retry scheduled (no false-positive cancel)")
    void followerRetryKeepsOnTransientFailure() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        Map<Long, ScheduledFuture<?>> followerRetries =
                (Map<Long, ScheduledFuture<?>>) ReflectionTestUtils.getField(manager, "followerRetryFutures");
        followerRetries.put(100L, future);

        when(channelService.getChannel(100L)).thenThrow(new RuntimeException("connection refused"));

        manager.followerRetry(100L);

        assertTrue(manager.hasFollowerRetry(100L),
                "Transient lookup errors must not cancel the follower retry");
        verify(future, never()).cancel(any(Boolean.class));
    }

    @Test
    @DisplayName("non-leader reconcile: disabled channel on another node triggers local stop")
    void nonLeaderReconcileStopsOnDisabled() {
        // Seed a non-leader active adapter (no lease, no heartbeat) — this is
        // the state a Feishu-webhook or Telegram-webhook node lives in.
        TrackingAdapter webhookAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        seedNonLeaderState(60L, webhookAdapter, LocalDateTime.of(2026, 1, 1, 0, 0));

        ChannelEntity disabled = entity(60L, "feishu");
        disabled.setEnabled(false);
        disabled.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.getChannel(60L)).thenReturn(disabled);

        manager.reconcileChannel(60L, "webhook-channel");

        assertFalse(manager.getAdapter(60L).isPresent(),
                "Non-leader reconcile must stop the local adapter when admin disables on another node");
        assertTrue(webhookAdapter.stopped.get());
    }

    @Test
    @DisplayName("non-leader → leader-required flip: winner becomes leader (no direct start without election)")
    void nonLeaderToLeaderFlipWinsElection() {
        // Seed a non-leader webhook adapter (no lease).
        TrackingAdapter webhookAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        seedNonLeaderState(80L, webhookAdapter, LocalDateTime.of(2026, 1, 1, 0, 0));

        // New config flips into leader-required mode.
        TrackingAdapter wsAdapter = new TrackingAdapter(); // requiresSingleLeader=true
        ChannelManager spied = spy(manager);
        doReturn(wsAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        // This node wins the election.
        LeaderLease lease = mock(LeaderLease.class);
        when(election.tryAcquire(anyString())).thenReturn(Optional.of(lease));

        ChannelEntity flipped = entity(80L, "feishu");
        flipped.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));

        spied.applyConfigChange(80L, flipped);

        assertTrue(webhookAdapter.stopped.get(), "Old non-leader adapter must be stopped");
        assertTrue(wsAdapter.started.get(), "New leader-required adapter starts only after we won the election");
        // Lease is recorded so the heartbeat can extend it.
        @SuppressWarnings("unchecked")
        Map<Long, LeaderLease> leases =
                (Map<Long, LeaderLease>) ReflectionTestUtils.getField(spied, "activeLeases");
        assertSame(lease, leases.get(80L), "Won lease must be tracked under the channel id");
        verify(election, times(1)).tryAcquire("feishu:80");
    }

    @Test
    @DisplayName("non-leader → leader-required flip: loser does NOT start the new adapter and enters follower retry")
    void nonLeaderToLeaderFlipLosesElection() {
        TrackingAdapter webhookAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        seedNonLeaderState(81L, webhookAdapter, LocalDateTime.of(2026, 1, 1, 0, 0));

        TrackingAdapter wsAdapter = new TrackingAdapter();
        ChannelManager spied = spy(manager);
        doReturn(wsAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        // Another node already holds the lease.
        when(election.tryAcquire(anyString())).thenReturn(Optional.empty());

        ChannelEntity flipped = entity(81L, "feishu");
        flipped.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));

        spied.applyConfigChange(81L, flipped);

        assertTrue(webhookAdapter.stopped.get(), "Old non-leader adapter must be stopped");
        assertFalse(wsAdapter.started.get(),
                "Loser must NOT call newAdapter.start() — that would open a duplicate WS bypassing the leader gate");
        assertFalse(spied.getAdapter(81L).isPresent());
        assertTrue(spied.hasFollowerRetry(81L), "Loser must enter follower retry to take over if the current leader dies");
        verify(election, times(1)).tryAcquire("feishu:81");
    }

    @Test
    @DisplayName("non-leader reconcile: config change on another node propagates via stop+start")
    void nonLeaderReconcileAppliesConfigUpdate() {
        TrackingAdapter oldAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        seedNonLeaderState(61L, oldAdapter, LocalDateTime.of(2026, 1, 1, 0, 0));

        TrackingAdapter newAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        ChannelManager spied = spy(manager);
        doReturn(newAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        ChannelEntity updated = entity(61L, "feishu");
        updated.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));
        when(channelService.getChannel(61L)).thenReturn(updated);

        spied.reconcileChannel(61L, "webhook-channel");

        assertTrue(oldAdapter.stopped.get(), "Old non-leader adapter must be stopped");
        assertTrue(newAdapter.started.get(), "New non-leader adapter must be started");
        assertTrue(spied.getAdapter(61L).isPresent());
    }

    @Test
    @DisplayName("restartChannel: when we hold a lease and new mode is non-leader, release the lease via stop+start")
    void restartChannelDetectsLeaseFlip() {
        LeaderLease lease = mock(LeaderLease.class);
        seedLeaderState(70L, adapter, lease, LocalDateTime.of(2026, 1, 1, 0, 0));

        // Make createAdapter produce a non-leader adapter for the restart.
        // Without the lease-aware check, restartChannel would take the
        // hot-swap path and leave the lease, heartbeat, and
        // lastSeenChannelUpdateTime around to be cleaned up only by the
        // next heartbeat tick — causing an additional restart.
        TrackingAdapter newAdapter = new TrackingAdapter() {
            @Override public boolean requiresSingleLeader() { return false; }
        };
        ChannelManager spied = spy(manager);
        doReturn(newAdapter).when(spied).createAdapter(any(ChannelEntity.class));

        ChannelEntity updated = entity(70L, "feishu");
        updated.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 5));
        when(channelService.getChannel(70L)).thenReturn(updated);

        spied.restartChannel(70L);

        verify(lease, times(1)).release();
        @SuppressWarnings("unchecked")
        Map<Long, LeaderLease> leases =
                (Map<Long, LeaderLease>) ReflectionTestUtils.getField(spied, "activeLeases");
        assertFalse(leases.containsKey(70L), "Lease must be removed from activeLeases after the mode flip");
        assertTrue(adapter.stopped.get());
        assertTrue(newAdapter.started.get());
    }

    @Test
    @DisplayName("stopAll releases plugin leases and cancels plugin heartbeats (no leak on shutdown)")
    @SuppressWarnings("unchecked")
    void stopAllReleasesPluginLeases() {
        ChannelAdapter pluginAdapter = mock(ChannelAdapter.class);
        when(pluginAdapter.getChannelType()).thenReturn("custom-im");
        when(pluginAdapter.getDisplayName()).thenReturn("custom-im");
        LeaderLease pluginLease = mock(LeaderLease.class);
        ScheduledFuture<?> pluginHeartbeat = mock(ScheduledFuture.class);

        Map<String, ChannelAdapter> pluginChannels =
                (Map<String, ChannelAdapter>) ReflectionTestUtils.getField(manager, "pluginChannels");
        Map<String, LeaderLease> pluginLeases =
                (Map<String, LeaderLease>) ReflectionTestUtils.getField(manager, "pluginLeases");
        Map<String, ScheduledFuture<?>> pluginHeartbeats =
                (Map<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(manager, "pluginHeartbeatFutures");
        pluginChannels.put("my-plugin", pluginAdapter);
        pluginLeases.put("my-plugin", pluginLease);
        pluginHeartbeats.put("my-plugin", pluginHeartbeat);

        manager.stopAll();

        verify(pluginAdapter, times(1)).stop();
        verify(pluginLease, times(1)).release();
        verify(pluginHeartbeat, times(1)).cancel(false);
        assertTrue(pluginChannels.isEmpty());
        assertTrue(pluginLeases.isEmpty());
        assertTrue(pluginHeartbeats.isEmpty());
    }

    // ==================== helpers ====================

    private ChannelEntity entity(Long id, String type) {
        ChannelEntity e = new ChannelEntity();
        e.setId(id);
        e.setName("test-" + id);
        e.setChannelType(type);
        e.setEnabled(true);
        return e;
    }

    private void seedLeaderState(Long id, ChannelAdapter adapter, LeaderLease lease,
                                 LocalDateTime updateTime) {
        @SuppressWarnings("unchecked")
        Map<Long, ChannelAdapter> active =
                (Map<Long, ChannelAdapter>) ReflectionTestUtils.getField(manager, "activeAdapters");
        @SuppressWarnings("unchecked")
        Map<Long, LeaderLease> leases =
                (Map<Long, LeaderLease>) ReflectionTestUtils.getField(manager, "activeLeases");
        active.put(id, adapter);
        leases.put(id, lease);
        lastSeenMap().put(id, updateTime);
    }

    /**
     * Seed a non-leader-required active adapter: appears in
     * {@code activeAdapters} and {@code lastSeenChannelUpdateTime}, but
     * no entry in {@code activeLeases} / {@code heartbeatFutures} (those
     * only exist for leader-required modes).
     */
    private void seedNonLeaderState(Long id, ChannelAdapter adapter, LocalDateTime updateTime) {
        @SuppressWarnings("unchecked")
        Map<Long, ChannelAdapter> active =
                (Map<Long, ChannelAdapter>) ReflectionTestUtils.getField(manager, "activeAdapters");
        active.put(id, adapter);
        lastSeenMap().put(id, updateTime);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, LocalDateTime> lastSeenMap() {
        return (Map<Long, LocalDateTime>) ReflectionTestUtils.getField(manager, "lastSeenChannelUpdateTime");
    }

    /**
     * Minimal adapter that records start/stop without opening any
     * upstream connection — the tests need observable state, not real
     * IM behavior.
     */
    private static class TrackingAdapter implements ChannelAdapter {
        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean stopped = new AtomicBoolean(false);

        @Override public void start() { started.set(true); }
        @Override public void stop() { stopped.set(true); }
        @Override public boolean isRunning() { return started.get() && !stopped.get(); }
        @Override public void onMessage(ChannelMessage message) {}
        @Override public void sendMessage(String targetId, String content) {}
        @Override public void sendContentParts(String targetId, List<MessageContentPart> parts) {}
        @Override public String getChannelType() { return "feishu"; }
        @Override public boolean requiresSingleLeader() { return true; }
    }
}
