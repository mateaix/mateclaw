package vip.mate.channel.leader;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelLeaderElectionTest {

    @Test
    @DisplayName("tryAcquire returns empty when LockProvider rejects (another node owns the lock)")
    void tryAcquireWhenLockHeldElsewhere() {
        LockProvider provider = mock(LockProvider.class);
        when(provider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());

        ChannelLeaderElection election = new ChannelLeaderElection(provider);
        Optional<LeaderLease> lease = election.tryAcquire("feishu:42");

        assertTrue(lease.isEmpty(), "Expected empty optional when lock is held elsewhere");
    }

    @Test
    @DisplayName("tryAcquire returns a lease when LockProvider grants the lock")
    void tryAcquireWhenLockGranted() {
        LockProvider provider = mock(LockProvider.class);
        SimpleLock simpleLock = mock(SimpleLock.class);
        when(provider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));

        ChannelLeaderElection election = new ChannelLeaderElection(provider);
        Optional<LeaderLease> lease = election.tryAcquire("qq:7");

        assertTrue(lease.isPresent());
        assertEquals("channel-leader:qq:7", lease.get().getName());
    }

    @Test
    @DisplayName("Lock name is prefixed so leases don't collide with other ShedLock users (e.g. cron)")
    void lockNameIsPrefixed() {
        LockProvider provider = mock(LockProvider.class);
        SimpleLock simpleLock = mock(SimpleLock.class);
        when(provider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));

        ChannelLeaderElection election = new ChannelLeaderElection(provider);
        election.tryAcquire("feishu:42");

        ArgumentCaptor<LockConfiguration> captor = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(provider).lock(captor.capture());
        assertEquals("channel-leader:feishu:42", captor.getValue().getName());
    }

    @Test
    @DisplayName("Lease extend() reports success when ShedLock returns a new SimpleLock")
    void leaseExtendSuccess() {
        SimpleLock current = mock(SimpleLock.class);
        SimpleLock next = mock(SimpleLock.class);
        when(current.extend(any(Duration.class), any(Duration.class))).thenReturn(Optional.of(next));

        LeaderLease lease = new LeaderLease("test", current);
        assertTrue(lease.extend(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("Lease extend() reports failure when ShedLock returns empty (lock lost)")
    void leaseExtendLost() {
        SimpleLock current = mock(SimpleLock.class);
        when(current.extend(any(Duration.class), any(Duration.class))).thenReturn(Optional.empty());

        LeaderLease lease = new LeaderLease("test", current);
        assertFalse(lease.extend(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("Lease extend() swallows exceptions and reports failure — heartbeats must not crash the scheduler")
    void leaseExtendCatchesException() {
        SimpleLock current = mock(SimpleLock.class);
        when(current.extend(any(Duration.class), any(Duration.class)))
                .thenThrow(new RuntimeException("db connection lost"));

        LeaderLease lease = new LeaderLease("test", current);
        assertFalse(lease.extend(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("Lease release() unlocks the underlying SimpleLock exactly once even if called twice")
    void leaseReleaseIdempotent() {
        SimpleLock simpleLock = mock(SimpleLock.class);
        LeaderLease lease = new LeaderLease("test", simpleLock);

        lease.release();
        lease.release();

        verify(simpleLock, times(1)).unlock();
    }

    @Test
    @DisplayName("Lease release() swallows unlock exceptions so shutdown can't be blocked by them")
    void leaseReleaseCatchesException() {
        SimpleLock simpleLock = mock(SimpleLock.class);
        doThrow(new RuntimeException("db gone")).when(simpleLock).unlock();

        LeaderLease lease = new LeaderLease("test", simpleLock);
        assertDoesNotThrow(lease::release);
    }

    @Test
    @DisplayName("Once released, extend() always returns false without touching the underlying lock")
    void extendAfterReleaseIsFalse() {
        SimpleLock simpleLock = mock(SimpleLock.class);
        LeaderLease lease = new LeaderLease("test", simpleLock);

        lease.release();
        assertFalse(lease.extend(Duration.ofSeconds(60)));
        verify(simpleLock, never()).extend(any(Duration.class), any(Duration.class));
    }
}
