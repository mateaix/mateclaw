package vip.mate.system.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link SettingCrypto}: AES-GCM round-trips, ciphertext is prefixed and
 * randomized per call, and legacy plaintext (no prefix) passes through so
 * secrets stay readable during migration.
 */
class SettingCryptoTest {

    private final SettingCrypto crypto = new SettingCrypto("unit-test-key");

    @Test
    @DisplayName("encrypt → decrypt round-trips, ciphertext is prefixed and differs from plaintext")
    void roundTrip() {
        String secret = "wx-app-secret-1234567890";
        String enc = crypto.encrypt(secret);
        assertTrue(enc.startsWith("enc:v1:"), "ciphertext must carry the version prefix");
        assertNotEquals(secret, enc);
        assertEquals(secret, crypto.decrypt(enc));
    }

    @Test
    @DisplayName("legacy plaintext (no prefix) is returned unchanged")
    void legacyPlaintextPassthrough() {
        assertEquals("old-plain-secret", crypto.decrypt("old-plain-secret"));
    }

    @Test
    @DisplayName("each encryption uses a fresh IV → different ciphertext, same plaintext")
    void randomizedIv() {
        String a = crypto.encrypt("same-value");
        String b = crypto.encrypt("same-value");
        assertNotEquals(a, b, "distinct IVs must yield distinct ciphertext");
        assertEquals("same-value", crypto.decrypt(a));
        assertEquals("same-value", crypto.decrypt(b));
    }

    @Test
    @DisplayName("blank/null pass through untouched")
    void blankPassthrough() {
        assertEquals("", crypto.encrypt(""));
        assertNull(crypto.encrypt(null));
        assertNull(crypto.decrypt(null));
    }

    @Test
    @DisplayName("wrong key cannot read another key's ciphertext")
    void wrongKeyFailsClosed() {
        String enc = crypto.encrypt("top-secret");
        String recovered = new SettingCrypto("a-different-key").decrypt(enc);
        assertEquals("", recovered, "a wrong key must not return the real secret");
    }
}
