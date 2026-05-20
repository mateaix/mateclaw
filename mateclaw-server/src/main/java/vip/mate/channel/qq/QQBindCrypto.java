package vip.mate.channel.qq;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM helpers for the QQ scan-to-bind onboarding flow.
 *
 * <p>The bind portal encrypts the bot {@code client_secret} with a key
 * supplied by this server, so the plaintext secret never travels in the
 * clear. Ciphertext layout returned by the portal is:
 *
 * <pre>base64( IV(12 bytes) ‖ ciphertext(N bytes) ‖ AuthTag(16 bytes) )</pre>
 */
final class QQBindCrypto {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private QQBindCrypto() {}

    /** Generate a fresh 256-bit AES key, base64-encoded. */
    static String generateKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Decrypt an AES-256-GCM ciphertext produced by the bind portal.
     *
     * @param encryptedBase64 base64-encoded {@code IV ‖ ciphertext ‖ tag}
     * @param keyBase64       base64 AES key (same one passed to create_bind_task)
     * @return decrypted UTF-8 plaintext
     */
    static String decryptSecret(String encryptedBase64, String keyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        byte[] raw = Base64.getDecoder().decode(encryptedBase64);
        if (raw.length < IV_BYTES + (TAG_BITS / 8)) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(raw, 0, iv, 0, IV_BYTES);
        byte[] ciphertextWithTag = new byte[raw.length - IV_BYTES];
        System.arraycopy(raw, IV_BYTES, ciphertextWithTag, 0, ciphertextWithTag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }
}
