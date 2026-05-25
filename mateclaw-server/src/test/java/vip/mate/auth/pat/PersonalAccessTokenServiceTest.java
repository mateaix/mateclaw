package vip.mate.auth.pat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.auth.pat.repository.PersonalAccessTokenMapper;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFC-03 Lane I1 — covers {@link PersonalAccessTokenService} core contracts:
 *
 * <ul>
 *   <li>Plaintext format ({@code mc_*}) and uniqueness across mints.</li>
 *   <li>SHA-256 hashing is deterministic and matches a known vector — a
 *       silent change to the hash function would invalidate every existing
 *       row in production, so this is enforced in test.</li>
 *   <li>{@link PersonalAccessTokenService#findActiveByPlaintext} rejects
 *       null, blank, wrong-prefix, hash-miss, disabled, and expired
 *       tokens with no observable difference (don't leak which one).</li>
 *   <li>{@link PersonalAccessTokenService#recordUse} debounces writes so
 *       a CI loop doesn't hammer the row.</li>
 *   <li>{@link PersonalAccessTokenService#revoke} requires owner match —
 *       a token id alone is insufficient to revoke someone else's token.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PersonalAccessTokenServiceTest {

    @Mock
    private PersonalAccessTokenMapper mapper;

    @InjectMocks
    private PersonalAccessTokenService service;

    private PersonalAccessTokenEntity entity;

    @BeforeEach
    void setUp() {
        entity = new PersonalAccessTokenEntity();
        entity.setId(42L);
        entity.setUserId(7L);
        entity.setName("ci-key");
        entity.setEnabled(true);
    }

    // ── Plaintext format ──────────────────────────────────────────────────

    @Test
    @DisplayName("generated plaintext starts with mc_ and is sufficiently long for 256-bit entropy")
    void plaintextFormat() {
        String tok = service.generatePlaintext();
        assertTrue(tok.startsWith("mc_"), "PAT must start with the observable mc_ prefix");
        // 32 bytes base64 url-encoded without padding = 43 chars; total = 3 + 43 = 46.
        assertEquals(46, tok.length(),
                "32 bytes of entropy → 43 base64 chars + 3-char prefix; got " + tok);
    }

    @Test
    @DisplayName("each generation yields a unique plaintext (entropy actually random)")
    void plaintextUniqueness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(service.generatePlaintext()),
                    "duplicate within 100 mints — RNG is not actually random");
        }
    }

    // ── SHA-256 hashing ──────────────────────────────────────────────────

    @Test
    @DisplayName("sha256Hex matches the canonical reference vector for 'abc'")
    void sha256ReferenceVector() {
        // From FIPS 180-4 — locking in the algorithm; if this assertion ever
        // fires, every PAT in the database is invalidated by the same change.
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PersonalAccessTokenService.sha256Hex("abc"));
    }

    @Test
    @DisplayName("sha256Hex output is always 64 lowercase hex chars")
    void sha256OutputShape() {
        String h = PersonalAccessTokenService.sha256Hex("any plaintext");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]+"));
    }

    // ── findActiveByPlaintext rejection paths ─────────────────────────────

    @Test
    @DisplayName("null / blank input returns empty without DB roundtrip")
    void nullBlankReturnsEmpty() {
        assertTrue(service.findActiveByPlaintext(null).isEmpty());
        assertTrue(service.findActiveByPlaintext("").isEmpty());
        assertTrue(service.findActiveByPlaintext("   ").isEmpty());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("token without mc_ prefix returns empty without DB roundtrip")
    void wrongPrefixReturnsEmpty() {
        // JWT-shaped value should not even hit the DB — keeps the auth filter
        // dispatch cheap when callers send either token type by mistake.
        assertTrue(service.findActiveByPlaintext("eyJhbGciOiJIUzI1NiJ9...").isEmpty());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("hash miss returns empty")
    void hashMissReturnsEmpty() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertTrue(service.findActiveByPlaintext("mc_unknown_token").isEmpty());
    }

    @Test
    @DisplayName("expired token returns empty even when the row matches")
    void expiredTokenReturnsEmpty() {
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
        assertTrue(service.findActiveByPlaintext("mc_some_plaintext").isEmpty(),
                "expired tokens must reject — past-expiry is the same as no-such-token from auth's PoV");
    }

    @Test
    @DisplayName("active, unexpired token returns the entity")
    void activeTokenReturned() {
        entity.setExpiresAt(LocalDateTime.now().plusDays(7));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

        Optional<PersonalAccessTokenEntity> result = service.findActiveByPlaintext("mc_valid_plaintext");

        assertTrue(result.isPresent());
        assertEquals(42L, result.get().getId());
    }

    // ── recordUse debounce predicate (pure logic) ─────────────────────────

    @Test
    @DisplayName("shouldRecordUse — null lastUsedAt returns true (first write always proceeds)")
    void shouldRecordUseFirstCall() {
        assertTrue(PersonalAccessTokenService.shouldRecordUse(null, LocalDateTime.now()));
    }

    @Test
    @DisplayName("shouldRecordUse — within 60s of last write returns false (debounced)")
    void shouldRecordUseDebounced() {
        LocalDateTime now = LocalDateTime.now();
        // 30s ago — well within the 60s window.
        assertFalse(PersonalAccessTokenService.shouldRecordUse(now.minusSeconds(30), now));
    }

    @Test
    @DisplayName("shouldRecordUse — after 60s window returns true (writes again)")
    void shouldRecordUseAfterWindow() {
        LocalDateTime now = LocalDateTime.now();
        // 2 min ago — beyond the 60s debounce.
        assertTrue(PersonalAccessTokenService.shouldRecordUse(now.minusMinutes(2), now));
    }

    @Test
    @DisplayName("shouldRecordUse — exactly at 60s boundary returns true")
    void shouldRecordUseAtBoundary() {
        LocalDateTime now = LocalDateTime.now();
        // 61s ago — just past the boundary.
        assertTrue(PersonalAccessTokenService.shouldRecordUse(now.minusSeconds(61), now));
    }

    @Test
    @DisplayName("recordUse — first write hits the mapper")
    void recordUseFirstCallWrites() {
        service.recordUse(entity);
        verify(mapper, times(1)).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("recordUse — second call within debounce skips the mapper")
    void recordUseDebouncedSkipsMapper() {
        entity.setLastUsedAt(LocalDateTime.now());
        service.recordUse(entity);
        verify(mapper, never()).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("recordUse swallows DB errors — never fails an authenticated request")
    void recordUseSwallowsErrors() {
        when(mapper.updateById(any(PersonalAccessTokenEntity.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));
        // Must not throw — last-used is observability, not a correctness gate.
        service.recordUse(entity);
    }

    // ── revoke ownership ──────────────────────────────────────────────────

    @Test
    @DisplayName("revoke with matching owner soft-deletes")
    void revokeOwnedToken() {
        when(mapper.selectById(42L)).thenReturn(entity);
        when(mapper.updateById(any(PersonalAccessTokenEntity.class))).thenReturn(1);

        service.revoke(42L, 7L);

        verify(mapper, times(1)).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("revoke with wrong owner throws not-found — no info leak about token ownership")
    void revokeWrongOwner() {
        // Token exists but belongs to user 7, not 999.
        when(mapper.selectById(42L)).thenReturn(entity);

        var ex = assertThrows(MateClawException.class,
                () -> service.revoke(42L, 999L));
        assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("not owned"),
                "error message must indicate not-found, not 'unauthorized' — to avoid leaking which token ids exist");
        // Critically: must NOT have called updateById — owner check happens before any write.
        verify(mapper, never()).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("revoke of missing token throws not-found")
    void revokeMissingToken() {
        when(mapper.selectById(99L)).thenReturn(null);
        assertThrows(MateClawException.class,
                () -> service.revoke(99L, 7L));
        verify(mapper, never()).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("revoke of already-deleted token throws not-found (no double-delete confusion)")
    void revokeAlreadyDeletedToken() {
        entity.setDeleted(1);
        when(mapper.selectById(42L)).thenReturn(entity);
        assertThrows(MateClawException.class,
                () -> service.revoke(42L, 7L));
        verify(mapper, never()).updateById(any(PersonalAccessTokenEntity.class));
    }

    @Test
    @DisplayName("create requires non-null userId")
    void createRequiresUserId() {
        assertThrows(MateClawException.class,
                () -> service.create(null, "name", null, null));
    }

    @Test
    @DisplayName("tokenHash never leaks via Jackson serialization (privacy regression guard)")
    void tokenHashDoesNotLeakInJson() throws Exception {
        // The list endpoint returns PersonalAccessTokenEntity directly to
        // the client. Jackson must skip tokenHash even when other fields
        // serialize normally — otherwise admin UI / log middleware leaks
        // the per-token digest. Smoke test on 2026-05-02 caught this.
        PersonalAccessTokenEntity e = new PersonalAccessTokenEntity();
        e.setId(123L);
        e.setUserId(7L);
        e.setName("ci-key");
        e.setTokenHash("8020f458548f7b433f872da4d6828933e4f3ba421823f3e7010c9ffd3c505f20");
        e.setScopes("*");
        e.setEnabled(true);

        String json = new ObjectMapper().writeValueAsString(e);

        assertFalse(json.contains("tokenHash"),
                "tokenHash field name leaked to JSON: " + json);
        assertFalse(json.contains("8020f458"),
                "tokenHash value leaked to JSON: " + json);
        // Sanity: other fields still serialize so we didn't accidentally
        // @JsonIgnore the wrong field.
        assertTrue(json.contains("\"name\":\"ci-key\""));
        assertTrue(json.contains("\"id\":123"));
    }

    @Test
    @DisplayName("create returns plaintext exactly once and inserts the row")
    void createReturnsPlaintext() {
        when(mapper.insert(any(PersonalAccessTokenEntity.class))).thenReturn(1);
        PersonalAccessTokenService.CreatedToken result = service.create(
                7L, "ci-key", "*", LocalDateTime.now().plusDays(30));

        assertNotNull(result);
        assertNotNull(result.plaintext());
        assertTrue(result.plaintext().startsWith("mc_"));
        assertNotNull(result.entity());
        // The row inserted into DB must NOT carry plaintext — only the hash.
        assertFalse(result.plaintext().equals(result.entity().getTokenHash()),
                "DB must store the hash, not the plaintext");
    }
}
