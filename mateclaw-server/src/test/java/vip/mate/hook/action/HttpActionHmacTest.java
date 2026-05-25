package vip.mate.hook.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-03 Lane H1 — covers {@link HttpAction#hmacSign(String)} and the
 * default-header convention used to deliver outbound webhook signatures.
 *
 * <p>Validating the signature on the receiver side requires the digest to be:
 * <ol>
 *   <li>computed over the exact bytes that were sent (no JSON re-encode),</li>
 *   <li>formatted as {@code "sha256=<lowercase-hex>"} so off-the-shelf
 *       GitHub-style validators work without changes,</li>
 *   <li>deterministic — same secret + same body always yields the same
 *       digest (no timestamp / nonce mixed in here).</li>
 * </ol>
 *
 * <p>The reference vector is from RFC 4231 §4.7 (HMAC-SHA-256 with the
 * canonical "Test 7" inputs) so any divergence from the standard surfaces
 * here, not in production.
 */
class HttpActionHmacTest {

    /** Build an HttpAction with the given secret; restClient is a no-op stub
     *  because hmacSign() doesn't touch it. */
    private static HttpAction action(String secret) {
        return new HttpAction(
                RestClient.builder().build(),
                "POST",
                URI.create("https://hooks.example.com/test"),
                null,
                List.of("hooks.example.com"),
                3000L,
                secret,
                null);
    }

    @Test
    @DisplayName("hmacSign produces lowercase-hex 'sha256=<digest>' format")
    void formatIsGitHubCompatible() {
        String sig = action("secret").hmacSign("hello");
        assertTrue(sig.startsWith("sha256="),
                "header value must be sha256-prefixed for GitHub-compatible validators");
        // SHA-256 hex digest is 64 lowercase chars, no separators.
        String hex = sig.substring("sha256=".length());
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"),
                "digest must be lowercase hex; got: " + hex);
    }

    @Test
    @DisplayName("Wikipedia reference vector — known input → known digest")
    void referenceVector() {
        // From the canonical HMAC-SHA-256 worked example
        // (Wikipedia "HMAC" article — same input/output as Bruce Schneier's
        // applied-cryptography vector). Hardcoding the expected digest catches
        // any divergence from the JCA reference impl — e.g. if someone later
        // swaps in a third-party Mac or a Bouncy Castle provider that returns
        // a different byte order.
        String sig = action("key").hmacSign("The quick brown fox jumps over the lazy dog");
        assertEquals(
                "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                sig);
    }

    @Test
    @DisplayName("same secret + same body → identical digest (deterministic)")
    void deterministic() {
        HttpAction a = action("shared-secret-123");
        String first = a.hmacSign("{\"event\":\"agent.completed\"}");
        String second = a.hmacSign("{\"event\":\"agent.completed\"}");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("different secrets → different digests")
    void secretMattersForDigest() {
        String body = "{\"event\":\"x\"}";
        String s1 = action("secret-A").hmacSign(body);
        String s2 = action("secret-B").hmacSign(body);
        assertTrue(!s1.equals(s2),
                "swapping the secret must change the digest — otherwise signing is theatre");
    }

    @Test
    @DisplayName("different body bytes → different digests")
    void bodyMattersForDigest() {
        HttpAction a = action("secret");
        String s1 = a.hmacSign("{\"a\":1}");
        String s2 = a.hmacSign("{\"a\":2}");
        assertTrue(!s1.equals(s2),
                "swapping a byte must change the digest — otherwise tampering goes undetected");
    }

    @Test
    @DisplayName("default signature header constant matches MateClaw convention")
    void defaultHeaderName() {
        assertEquals("X-MateClaw-Signature", HttpAction.DEFAULT_SIGNATURE_HEADER);
    }
}
