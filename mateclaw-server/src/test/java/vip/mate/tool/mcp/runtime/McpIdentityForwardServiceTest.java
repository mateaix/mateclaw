package vip.mate.tool.mcp.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link McpIdentityForwardService}: plaintext vs signed-token resolution,
 * fail-closed when token mode lacks a key, and that a minted token verifies
 * against the public key with the right claims.
 */
class McpIdentityForwardServiceTest {

    @AfterEach
    void clear() {
        ToolExecutionContext.clear();
    }

    private McpIdentityForwardService svc(McpIdentityForwardProperties p) {
        return new McpIdentityForwardService(p);
    }

    @Test
    @DisplayName("plaintext mode: injects username under USER_ARG")
    void plaintext() {
        ToolExecutionContext.set("c1", "alice");
        var p = new McpIdentityForwardProperties();
        Optional<McpIdentityForwardService.Injection> inj = svc(p).resolve(null, "my-api");
        assertThat(inj).isPresent();
        assertThat(inj.get().key()).isEqualTo(McpIdentityForwardProperties.USER_ARG);
        assertThat(inj.get().value()).isEqualTo("alice");
    }

    @Test
    @DisplayName("no authenticated user: nothing injected")
    void noUser() {
        var p = new McpIdentityForwardProperties();
        assertThat(svc(p).resolve(null, "my-api")).isEmpty();
    }

    @Test
    @DisplayName("token mode but no key: fail-closed (nothing injected)")
    void tokenModeNoKey() {
        ToolExecutionContext.set("c1", "alice");
        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);   // no private-key-pem
        assertThat(svc(p).resolve(null, "my-api")).isEmpty();
    }

    @Test
    @DisplayName("token mode: mints an RS256 JWT that verifies with the public key")
    void tokenMintAndVerify() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").genKeyPair();   // 2048 default
        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);
        p.getToken().setIssuer("mateclaw");
        p.getToken().setTtlSeconds(60);
        p.getToken().setPrivateKeyPem(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));

        ToolExecutionContext.set("c1", "alice");
        Optional<McpIdentityForwardService.Injection> inj = svc(p).resolve(null, "my-api");

        assertThat(inj).isPresent();
        assertThat(inj.get().key()).isEqualTo(McpIdentityForwardProperties.TOKEN_ARG);

        // The REST backend would do exactly this: verify with the public key.
        Claims claims = Jwts.parser()
                .verifyWith(kp.getPublic())
                .requireIssuer("mateclaw")
                .requireAudience("my-api")
                .build()
                .parseSignedClaims(inj.get().value())
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getExpiration()).isAfter(new Date());
        assertThat(claims.getId()).isNotBlank();   // jti present
    }

    @Test
    @DisplayName("audienceFor: explicit mapping wins, else server name")
    void audienceResolution() {
        var p = new McpIdentityForwardProperties();
        assertThat(p.audienceFor(42L, "svc")).isEqualTo("svc");          // default = name
        p.getToken().setAudiences(java.util.Map.of("svc", "https://api.internal"));
        assertThat(p.audienceFor(42L, "svc")).isEqualTo("https://api.internal");
    }
}
