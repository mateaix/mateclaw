package vip.mate.skill.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.exception.MateClawException;
import vip.mate.skill.repository.SkillSecretMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Locks in the security-sensitive bits of {@link SkillSecretService}:
 * AES round-trip, value masking, env-var-shaped key validation, and
 * cascade purge. Mapper queries are mocked — wrappers are opaque for
 * unit tests, so we only verify <em>which</em> mapper methods get hit
 * and what they receive.
 */
class SkillSecretServiceTest {

    private SkillSecretMapper mapper;
    private SkillSecretService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SkillSecretMapper.class);
        service = new SkillSecretService(mapper);
        ReflectionTestUtils.setField(service, "encryptKey", "TestKey-1234567");
    }

    @Test
    @DisplayName("put encrypts the plaintext before persisting (ciphertext != plaintext)")
    void putEncryptsBeforePersist() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.put(42L, "AIRTABLE_API_KEY", "pat_secret_value_123");

        ArgumentCaptor<SkillSecretEntity> captor = ArgumentCaptor.forClass(SkillSecretEntity.class);
        verify(mapper).insert((SkillSecretEntity) captor.capture());
        SkillSecretEntity stored = captor.getValue();
        assertEquals("AIRTABLE_API_KEY", stored.getSecretKey());
        assertNotEquals("pat_secret_value_123", stored.getEncryptedValue(),
                "stored value must be encrypted");
        assertTrue(stored.getEncryptedValue().length() >= 32,
                "AES hex output should be at least one block");
    }

    @Test
    @DisplayName("put → getDecrypted round-trip recovers the original plaintext")
    void roundTripRecoversPlaintext() {
        // Capture what put() persists, then feed it back to the mapper for getDecrypted.
        ArgumentCaptor<SkillSecretEntity> captor = ArgumentCaptor.forClass(SkillSecretEntity.class);
        when(mapper.selectOne(any())).thenReturn(null);

        service.put(7L, "TOKEN", "hello-world-12345");
        verify(mapper).insert((SkillSecretEntity) captor.capture());
        SkillSecretEntity stored = captor.getValue();
        // Wire the mapper to return the captured row on subsequent reads.
        when(mapper.selectList(any())).thenReturn(List.of(stored));

        Map<String, String> decrypted = service.getDecrypted(7L);
        assertEquals(1, decrypted.size());
        assertEquals("hello-world-12345", decrypted.get("TOKEN"));
    }

    @Test
    @DisplayName("put with existing row updates instead of inserting a duplicate")
    void putUpdatesExisting() {
        SkillSecretEntity existing = new SkillSecretEntity();
        existing.setId(1L);
        existing.setSkillId(42L);
        existing.setSecretKey("API_KEY");
        existing.setEncryptedValue("oldcipher");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.put(42L, "API_KEY", "new-value");

        verify(mapper).updateById(any(SkillSecretEntity.class));
        verify(mapper, times(0)).insert(any(SkillSecretEntity.class));
        assertNotEquals("oldcipher", existing.getEncryptedValue(),
                "encryptedValue must be replaced with the new ciphertext");
    }

    @Test
    @DisplayName("put with empty value short-circuits to remove (no insert/update)")
    void putEmptyDelegatesToRemove() {
        service.put(42L, "API_KEY", "");

        verify(mapper).delete(any());
        verify(mapper, times(0)).insert(any(SkillSecretEntity.class));
        verify(mapper, times(0)).updateById(any(SkillSecretEntity.class));
    }

    @Test
    @DisplayName("listSummaries returns masked previews; never plaintext")
    void listSummariesMasked() {
        SkillSecretEntity row = new SkillSecretEntity();
        row.setSkillId(7L);
        row.setSecretKey("TOKEN");
        // Encrypt a known value through the service so the test isn't
        // coupled to the AES output format directly.
        when(mapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<SkillSecretEntity> captor = ArgumentCaptor.forClass(SkillSecretEntity.class);
        service.put(7L, "TOKEN", "supersecret_credentials");
        verify(mapper).insert((SkillSecretEntity) captor.capture());
        when(mapper.selectList(any())).thenReturn(List.of(captor.getValue()));

        List<SkillSecretService.SecretSummary> summaries = service.listSummaries(7L);
        assertEquals(1, summaries.size());
        String preview = summaries.get(0).preview();
        assertFalse(preview.contains("supersecret"), "preview must not leak plaintext");
        assertTrue(preview.contains("•"), "preview should contain mask dots: " + preview);
    }

    @Test
    @DisplayName("getDecrypted returns empty map for null skillId without touching the mapper")
    void getDecryptedNullSkillIsNoop() {
        assertTrue(service.getDecrypted(null).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("rejects keys that aren't env-var-shaped; mapper never called")
    void rejectsBadKeys() {
        assertThrows(MateClawException.class, () -> service.put(1L, "with-dash", "v"));
        assertThrows(MateClawException.class, () -> service.put(1L, "1leading-digit", "v"));
        assertThrows(MateClawException.class, () -> service.put(1L, "", "v"));
        assertThrows(MateClawException.class, () -> service.put(1L, null, "v"));
        assertThrows(MateClawException.class, () -> service.put(null, "FOO", "v"));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("mask: <=4 chars → all dots; >4 → first 2 + dots + last 2")
    void maskShape() {
        assertEquals("ab••••yz", SkillSecretService.mask("abcdefxyz"));
        assertEquals("••••", SkillSecretService.mask("abc"));
        assertEquals("••••", SkillSecretService.mask(""));
        assertEquals("", SkillSecretService.mask(null));
    }

    @Test
    @DisplayName("purgeForSkill delegates to the cascade hard-delete query")
    void purgeDelegates() {
        when(mapper.hardDeleteBySkillId(42L)).thenReturn(3);

        int purged = service.purgeForSkill(42L);

        assertEquals(3, purged);
        verify(mapper).hardDeleteBySkillId(42L);
    }
}
