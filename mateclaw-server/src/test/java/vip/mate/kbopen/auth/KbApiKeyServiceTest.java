package vip.mate.kbopen.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.auth.KbApiKeyService.AuthResult;
import vip.mate.kbopen.auth.KbApiKeyService.CreatedKey;
import vip.mate.kbopen.auth.model.KbApiKeyBindingEntity;
import vip.mate.kbopen.auth.model.KbApiKeyEntity;
import vip.mate.kbopen.auth.repository.KbApiKeyBindingMapper;
import vip.mate.kbopen.auth.repository.KbApiKeyMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KbApiKeyService} covering the three P0-A security
 * requirements: R1 (auth lookup), R2 (rate-limit context), R3 (empty binding
 * rejection). Mirrors the #438/#439 IDOR test style.
 */
class KbApiKeyServiceTest {

    private KbApiKeyMapper keyMapper;
    private KbApiKeyBindingMapper bindingMapper;
    private TokenHashUtil tokenHashUtil;
    private KbApiKeyService service;

    @BeforeEach
    void setUp() {
        keyMapper = mock(KbApiKeyMapper.class);
        bindingMapper = mock(KbApiKeyBindingMapper.class);
        tokenHashUtil = new TokenHashUtil();
        service = new KbApiKeyService(keyMapper, bindingMapper, tokenHashUtil);
    }

    // ── R3: empty binding rejection ───────────────────────────────────────

    @Test
    @DisplayName("create with empty kbIds → 400 (R3: zero access is useless)")
    void createEmptyBindingRejected() {
        assertThatThrownBy(() -> service.create(1L, 100L, "test", "kb:*", Set.of(), null))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    @DisplayName("create with null kbIds → 400")
    void createNullBindingRejected() {
        assertThatThrownBy(() -> service.create(1L, 100L, "test", "kb:*", null, null))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("updateBindings to empty → 400 (cannot reduce to zero access)")
    void updateBindingsEmptyRejected() {
        assertThatThrownBy(() -> service.updateBindings(1L, Set.of()))
                .isInstanceOf(MateClawException.class);
    }

    // ── Create + authenticate round-trip ──────────────────────────────────

    @Test
    @DisplayName("create returns plaintext once; authenticate resolves it back")
    void createAndAuthenticateRoundTrip() {
        // Simulate MyBatis-Plus assigning an id on insert
        when(keyMapper.insert(any(KbApiKeyEntity.class))).thenAnswer(inv -> {
            KbApiKeyEntity e = inv.getArgument(0);
            e.setId(42L);
            return 1;
        });
        CreatedKey created = service.create(1L, 100L, "test-key", "kb:search", Set.of(10L, 20L), null);
        String plaintext = created.plaintext();

        assertThat(plaintext).startsWith(KbApiKeyService.KEY_PREFIX);
        assertThat(created.entity().getId()).isEqualTo(42L);

        // authenticate() hashes the plaintext and looks it up — set up the mapper
        // to return the created entity (whose hash matches).
        when(keyMapper.selectOne(any())).thenReturn(created.entity());
        when(bindingMapper.selectList(any())).thenReturn(java.util.List.of(
                bindingFor(42L, 10L),
                bindingFor(42L, 20L)));

        Optional<AuthResult> result = service.authenticate(plaintext);

        assertThat(result).isPresent();
        KbApiKeyContext ctx = result.get().context();
        assertThat(ctx.keyId()).isEqualTo(42L);
        assertThat(ctx.workspaceId()).isEqualTo(1L);
        assertThat(ctx.kbIds()).containsExactlyInAnyOrder(10L, 20L);
        assertThat(ctx.scopes()).contains("kb:search");
        assertThat(ctx.hasScope("kb:search")).isTrue();
        assertThat(ctx.hasScope("kb:read")).isFalse();
        assertThat(ctx.canAccessKb(10L)).isTrue();
        assertThat(ctx.canAccessKb(99L)).isFalse();
    }

    @Test
    @DisplayName("authenticate with wrong prefix → empty (not an mck_ key)")
    void authenticateWrongPrefix() {
        assertThat(service.authenticate("mc_something")).isEmpty();
        assertThat(service.authenticate("eyJ.jwt.token")).isEmpty();
    }

    @Test
    @DisplayName("authenticate with null/blank → empty")
    void authenticateNullBlank() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate("   ")).isEmpty();
    }

    @Test
    @DisplayName("authenticate with hash miss → empty")
    void authenticateHashMiss() {
        when(keyMapper.selectOne(any())).thenReturn(null);
        assertThat(service.authenticate("mck_nonexistent_key_value_here")).isEmpty();
    }

    @Test
    @DisplayName("authenticate expired key → empty")
    void authenticateExpired() {
        String plaintext = tokenHashUtil.generate(KbApiKeyService.KEY_PREFIX, 32);
        KbApiKeyEntity entity = entityWithHash(1L, 1L, "kb:*", plaintext);
        entity.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(keyMapper.selectOne(any())).thenReturn(entity);
        when(bindingMapper.selectList(any())).thenReturn(java.util.List.of());

        assertThat(service.authenticate(plaintext)).isEmpty();
    }

    @Test
    @DisplayName("authenticate disabled key → empty")
    void authenticateDisabled() {
        // The query already filters enabled=true, so selectOne returns null
        when(keyMapper.selectOne(any())).thenReturn(null);

        String plaintext = tokenHashUtil.generate(KbApiKeyService.KEY_PREFIX, 32);
        assertThat(service.authenticate(plaintext)).isEmpty();
    }

    // ── kb:* wildcard scope ───────────────────────────────────────────────

    @Test
    @DisplayName("kb:* scope grants all individual scopes")
    void wildcardScopeGrantsAll() {
        String plaintext = tokenHashUtil.generate(KbApiKeyService.KEY_PREFIX, 32);
        when(keyMapper.selectOne(any())).thenReturn(entityWithHash(1L, 1L, "kb:*", plaintext));
        when(bindingMapper.selectList(any())).thenReturn(java.util.List.of(bindingFor(1L, 10L)));

        KbApiKeyContext ctx = service.authenticate(plaintext).get().context();

        assertThat(ctx.hasScope("kb:search")).isTrue();
        assertThat(ctx.hasScope("kb:read")).isTrue();
        assertThat(ctx.hasScope("kb:list")).isTrue();
        assertThat(ctx.hasScope("kb:meta")).isTrue();
    }

    @Test
    @DisplayName("null scopes defaults to kb:*")
    void nullScopesDefaultsToWildcard() {
        String plaintext = tokenHashUtil.generate(KbApiKeyService.KEY_PREFIX, 32);
        when(keyMapper.selectOne(any())).thenReturn(entityWithHash(1L, 1L, null, plaintext));
        when(bindingMapper.selectList(any())).thenReturn(java.util.List.of(bindingFor(1L, 10L)));

        KbApiKeyContext ctx = service.authenticate(plaintext).get().context();

        assertThat(ctx.hasScope("kb:search")).isTrue();
    }

    // ── Revoke ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("revoke sets enabled=false + deleted=1")
    void revokeSoftDeletes() {
        KbApiKeyEntity entity = entity(1L, 1L, "kb:*");
        when(keyMapper.selectById(1L)).thenReturn(entity);

        service.revoke(1L, 1L);

        assertThat(entity.getEnabled()).isFalse();
        assertThat(entity.getDeleted()).isEqualTo(1);
        verify(keyMapper).updateById(entity);
    }

    @Test
    @DisplayName("revoke from wrong workspace → 404")
    void revokeWrongWorkspace() {
        KbApiKeyEntity entity = entity(1L, 1L, "kb:*"); // belongs to ws 1
        when(keyMapper.selectById(1L)).thenReturn(entity);

        assertThatThrownBy(() -> service.revoke(1L, 2L)) // caller in ws 2
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Create an entity whose tokenHash matches the given plaintext. */
    private KbApiKeyEntity entityWithHash(long id, long workspaceId, String scopes, String plaintext) {
        KbApiKeyEntity e = entity(id, workspaceId, scopes);
        e.setTokenHash(tokenHashUtil.hash(plaintext));
        return e;
    }

    private KbApiKeyEntity entity(long id, long workspaceId, String scopes) {
        KbApiKeyEntity e = new KbApiKeyEntity();
        e.setId(id);
        e.setWorkspaceId(workspaceId);
        e.setScopes(scopes);
        e.setEnabled(true);
        e.setDeleted(0);
        e.setRateLimitPerMin(60);
        return e;
    }

    private KbApiKeyBindingEntity bindingFor(long apiKeyId, long kbId) {
        KbApiKeyBindingEntity b = new KbApiKeyBindingEntity();
        b.setApiKeyId(apiKeyId);
        b.setKbId(kbId);
        return b;
    }
}
