package vip.mate.tool.mcp.runtime;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves what identity to inject into an opt-in MCP server's tool call, and
 * (in token mode) mints the signed assertion.
 *
 * <p>Two modes, per {@link McpIdentityForwardProperties}:
 * <ul>
 *   <li><b>plaintext</b> — returns {@code (__mateclaw_user__, username)}.</li>
 *   <li><b>token</b> — returns {@code (__mateclaw_token__, <RS256 JWT>)} with
 *       {@code sub}=username, {@code aud}=server audience, short {@code exp}. The
 *       REST backend verifies the signature with the matching public key, so it
 *       need not trust the MCP service or the transport.</li>
 * </ul>
 *
 * <p>The {@code sub} carries the MateClaw user identifier
 * ({@code ChatOrigin.requesterId}). If your backend keys authorization on an
 * immutable numeric id, resolve username→id before minting (left as a refinement
 * so this layer stays decoupled from the user store).
 *
 * <p>Fail-closed: when token mode is enabled but the signing key is missing or
 * unparseable, nothing is injected (the call goes out without identity and the
 * backend rejects it) rather than silently downgrading to plaintext.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class McpIdentityForwardService {

    private final McpIdentityForwardProperties properties;

    /** Lazily parsed signing key; {@code null} until first use / when unavailable. */
    private volatile PrivateKey signingKey;
    private volatile boolean keyParseAttempted;

    public McpIdentityForwardService(McpIdentityForwardProperties properties) {
        this.properties = properties;
    }

    public boolean forwardsTo(Long serverId, String serverName) {
        return properties.forwardsTo(serverId, serverName);
    }

    public String audienceFor(Long serverId, String serverName) {
        return properties.audienceFor(serverId, serverName);
    }

    /** The (key, value) to merge into the call arguments, or empty to inject nothing. */
    public record Injection(String key, String value) {}

    /**
     * Resolve the identity injection for a call. Empty when there is no
     * authenticated user (never fabricate identity), or when token mode is
     * enabled but the key is unavailable (fail-closed).
     */
    public Optional<Injection> resolve(ToolContext ctx, String audience) {
        String user = ToolExecutionContext.username(ctx);
        if (user == null || user.isBlank()) {
            return Optional.empty();
        }
        if (!properties.getToken().isEnabled()) {
            return Optional.of(new Injection(McpIdentityForwardProperties.USER_ARG, user));
        }
        String jwt = mint(user, audience);
        if (jwt == null) {
            return Optional.empty();   // fail-closed: token mode but no key
        }
        return Optional.of(new Injection(McpIdentityForwardProperties.TOKEN_ARG, jwt));
    }

    /** Mint a short-lived RS256 JWT, or {@code null} if the key is unavailable. */
    private String mint(String subject, String audience) {
        PrivateKey key = signingKey();
        if (key == null) {
            return null;
        }
        McpIdentityForwardProperties.Token t = properties.getToken();
        Instant now = Instant.now();
        try {
            return Jwts.builder()
                    .header().keyId(t.getKeyId()).and()
                    .issuer(t.getIssuer())
                    .subject(subject)
                    .audience().add(audience).and()
                    .id(UUID.randomUUID().toString())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(Duration.ofSeconds(Math.max(1, t.getTtlSeconds())))))
                    .signWith(key, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception e) {
            log.error("[McpIdentity] failed to mint identity token: {}", e.getMessage());
            return null;
        }
    }

    private PrivateKey signingKey() {
        PrivateKey k = signingKey;
        if (k != null) {
            return k;
        }
        if (keyParseAttempted) {
            return null;   // already tried and failed; don't spam parsing
        }
        synchronized (this) {
            if (signingKey != null) {
                return signingKey;
            }
            keyParseAttempted = true;
            String pem = properties.getToken().getPrivateKeyPem();
            if (pem == null || pem.isBlank()) {
                log.error("[McpIdentity] token mode enabled but mateclaw.mcp.identity-forward.token.private-key-pem is empty; "
                        + "identity tokens will NOT be issued (fail-closed)");
                return null;
            }
            try {
                String body = pem.replaceAll("-----BEGIN (.*)-----", "")
                        .replaceAll("-----END (.*)-----", "")
                        .replaceAll("\\s", "");
                byte[] der = Base64.getDecoder().decode(body);
                signingKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(der));
                log.info("[McpIdentity] loaded RS256 signing key (kid={})", properties.getToken().getKeyId());
            } catch (Exception e) {
                log.error("[McpIdentity] failed to parse private-key-pem (expect PKCS#8 RSA): {}", e.getMessage());
            }
            return signingKey;
        }
    }
}
