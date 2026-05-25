package vip.mate.system.featureflag;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import vip.mate.system.featureflag.repository.FeatureFlagMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeatureFlagService}.
 *
 * <p>The service is exercised against a mocked mapper so the test does not
 * depend on a database. All evaluation modes are covered:
 * disabled-master-switch, KB-whitelist hit/miss, percentage rollout
 * stability, unknown flags, and post-write invalidation.
 */
class FeatureFlagServiceTest {

    private FeatureFlagMapper mapper;
    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        mapper = mock(FeatureFlagMapper.class);
        when(mapper.selectList(ArgumentMatchers.<Wrapper<FeatureFlagEntity>>any()))
                .thenReturn(List.of());
        service = new FeatureFlagService(mapper);
        service.init();  // primes empty cache
    }

    @Test
    @DisplayName("Master switch off → isEnabled returns false even with whitelist hit")
    void disabled_returnsFalseEverywhere() {
        primeFlag(flag("wiki.test.disabled", false, "1,2", null, 100));

        assertThat(service.isEnabled("wiki.test.disabled")).isFalse();
        assertThat(service.isEnabledForKb("wiki.test.disabled", 1L)).isFalse();
        assertThat(service.isEnabledForKb("wiki.test.disabled", 99L)).isFalse();
    }

    @Test
    @DisplayName("Enabled with no whitelist and 0% rollout still returns true (no gate to fail)")
    void enabled_noWhitelist_zeroPercent_returnsTrue() {
        primeFlag(flag("wiki.test.simple", true, null, null, 0));

        assertThat(service.isEnabled("wiki.test.simple")).isTrue();
        assertThat(service.isEnabledForKb("wiki.test.simple", 42L)).isTrue();
    }

    @Test
    @DisplayName("KB whitelist gates by membership when context has kbId")
    void kbWhitelist_membersOnly() {
        primeFlag(flag("wiki.test.kbgated", true, "1,2,3", null, 0));

        assertThat(service.isEnabledForKb("wiki.test.kbgated", 1L)).isTrue();
        assertThat(service.isEnabledForKb("wiki.test.kbgated", 2L)).isTrue();
        assertThat(service.isEnabledForKb("wiki.test.kbgated", 99L)).isFalse();
    }

    @Test
    @DisplayName("KB whitelist with no kbId in context allows through (whitelist not applicable)")
    void kbWhitelist_noContext_passesThrough() {
        primeFlag(flag("wiki.test.kbgated2", true, "1,2,3", null, 0));
        // No kbId in context → kb whitelist not consulted; falls through to default true.
        assertThat(service.isEnabled("wiki.test.kbgated2")).isTrue();
    }

    @Test
    @DisplayName("User whitelist independently gates by user id")
    void userWhitelist_membersOnly() {
        primeFlag(flag("wiki.test.usergated", true, null, "10,20", 0));

        assertThat(service.isEnabledForUser("wiki.test.usergated", 10L)).isTrue();
        assertThat(service.isEnabledForUser("wiki.test.usergated", 99L)).isFalse();
    }

    @Test
    @DisplayName("Percentage rollout is deterministic for the same kbId across calls")
    void percentageRollout_stableForSameKey() {
        primeFlag(flag("wiki.test.rollout", true, null, null, 50));

        boolean first = service.isEnabledForKb("wiki.test.rollout", 7L);
        boolean second = service.isEnabledForKb("wiki.test.rollout", 7L);
        boolean third = service.isEnabledForKb("wiki.test.rollout", 7L);

        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(third);
    }

    @Test
    @DisplayName("Percentage rollout: 100% always passes, 0% rollout treated as no gate")
    void percentageRollout_boundaryValues() {
        primeFlag(flag("wiki.test.always", true, null, null, 100));
        primeFlag(flag("wiki.test.never_gate", true, null, null, 0));

        // 100% means rollout doesn't actually gate (logic only applies for 0<pct<100),
        // so no whitelist + 100% means "all true". Same for 0%.
        assertThat(service.isEnabledForKb("wiki.test.always", 999_999L)).isTrue();
        assertThat(service.isEnabledForKb("wiki.test.never_gate", 999_999L)).isTrue();
    }

    @Test
    @DisplayName("Unknown flag evaluates to false (fail-closed)")
    void unknownFlag_returnsFalse() {
        // No prime; mapper returns null on selectOne, MISSING is cached.
        assertThat(service.isEnabled("wiki.totally.fictional")).isFalse();
    }

    @Test
    @DisplayName("invalidate() clears cache and triggers refresh from DB")
    void invalidate_reloadsFromDb() {
        primeFlag(flag("wiki.test.refresh", false, null, null, 0));
        assertThat(service.isEnabled("wiki.test.refresh")).isFalse();

        // Operator flips the flag in DB.
        primeFlag(flag("wiki.test.refresh", true, null, null, 0));
        // Without invalidate the cached value is still false.
        // After invalidate the new value is visible.
        service.invalidate();
        assertThat(service.isEnabled("wiki.test.refresh")).isTrue();
    }

    @Test
    @DisplayName("isEnabled with null context degrades gracefully to empty context")
    void nullContext_treatedAsEmpty() {
        primeFlag(flag("wiki.test.simple2", true, null, null, 0));
        assertThat(service.isEnabled("wiki.test.simple2", null)).isTrue();
    }

    @Test
    @DisplayName("Mapper exception during loadOne does not propagate; flag becomes MISSING")
    void mapperException_failsClosed() {
        when(mapper.selectOne(ArgumentMatchers.<Wrapper<FeatureFlagEntity>>any()))
                .thenThrow(new RuntimeException("DB temporarily unavailable"));

        boolean result = service.isEnabled("wiki.flaky.flag");

        assertThat(result).isFalse();
        verify(mapper, atLeastOnce())
                .selectOne(ArgumentMatchers.<Wrapper<FeatureFlagEntity>>any());
    }

    // ==================== helpers ====================

    private FeatureFlagEntity flag(String key, boolean enabled, String kbWhitelist,
                                    String userWhitelist, Integer rollout) {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.setFlagKey(key);
        f.setEnabled(enabled);
        f.setWhitelistKbIds(kbWhitelist);
        f.setWhitelistUserIds(userWhitelist);
        f.setRolloutPercent(rollout);
        f.setDeleted(0);
        return f;
    }

    /** Sets up the mapper so that the given flag is returned for both selectOne and selectList. */
    private void primeFlag(FeatureFlagEntity flag) {
        when(mapper.selectOne(ArgumentMatchers.<Wrapper<FeatureFlagEntity>>any()))
                .thenReturn(flag);
        when(mapper.selectList(ArgumentMatchers.<Wrapper<FeatureFlagEntity>>any()))
                .thenReturn(List.of(flag));
    }
}
