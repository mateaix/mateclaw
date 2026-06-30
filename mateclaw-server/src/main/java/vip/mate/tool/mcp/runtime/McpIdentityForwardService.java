package vip.mate.tool.mcp.runtime;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
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
 * <p><b>Identity typing.</b> Not every requester is a MateClaw-authenticated
 * account. The resolved identity is typed so the REST backend can tell
 * "MateClaw authenticated this user" apart from "this is an external/anonymous
 * identifier" (see RFC: on-behalf-of identity typing):
 * <ul>
 *   <li><b>authenticated</b> — web-console login (JWT/PAT). {@code sub} = the
 *       user's immutable numeric id (carried on {@link ChatOrigin#requesterUserId()}).
 *       The backend may authorize on-behalf-of freely.</li>
 *   <li><b>anonymous</b> — webchat visitor / third-party {@code endUserId}. No
 *       MateClaw account backs it; {@code sub} = the visitor id. The backend must
 *       treat this as unauthenticated and decide for itself whether/how to serve.</li>
 *   <li><b>external</b> — IM sender (feishu/wecom/…). {@code sub} = the platform
 *       sender id; same caveat as anonymous.</li>
 *   <li><b>none</b> — cron / system / unattributed. Nothing is injected (fail-closed):
 *       we never assert identity on behalf of a non-user.</li>
 * </ul>
 *
 * <p>Two transport modes carry the typed identity, per {@link McpIdentityForwardProperties}:
 * <ul>
 *   <li><b>plaintext</b> — injects {@code <trust>:<subject>} under {@link McpIdentityForwardProperties#USER_ARG}.</li>
 *   <li><b>token</b> — injects an RS256 JWT under {@link McpIdentityForwardProperties#TOKEN_ARG}
 *       with {@code sub}, {@code trust}, {@code channel_type}, {@code aud}, short {@code exp}.
 *       The REST backend verifies the signature with the matching public key, so it
 *       need not trust the MCP service or the transport.</li>
 * </ul>
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

    /** Trust levels carried in the JWT {@code trust} claim / plaintext prefix. */
    static final String TRUST_AUTHENTICATED = "authenticated";
    static final String TRUST_ANONYMOUS = "anonymous";
    static final String TRUST_EXTERNAL = "external";

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

    /** A typed identity resolved from the request origin. Empty = inject nothing. */
    record ResolvedIdentity(String subject, String trust, String channelType) {
        static final ResolvedIdentity NONE = new ResolvedIdentity(null, null, null);
        boolean present() { return subject != null && !subject.isBlank(); }
    }

    /**
     * Classify the requester behind a tool call into a typed identity. Returns
     * {@link ResolvedIdentity#NONE} for cron / system / unattributed origins
     * (never assert identity on behalf of a non-user).
     *
     * <p>Source channel is read from {@link ChatOrigin} (carried on the
     * {@link ToolContext}); when the context carries no origin it falls back to
     * the legacy {@link ToolExecutionContext} username (treated as web/authenticated
     * only if non-blank — preserves the original contract for the ThreadLocal path).
     */
    ResolvedIdentity classify(ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        // Cron / system / unattributed: never forward identity.
        if (origin.cronOrigin() || "system".equals(origin.requesterId())) {
            return ResolvedIdentity.NONE;
        }
        String channel = origin.channelType();
        // Authenticated web account: MateClaw vouches for this user. Prefer the
        // immutable numeric id when available (web-console login); fall back to
        // the username when only the ThreadLocal path supplied identity.
        if (channel == null || channel.isBlank() || "web".equals(channel)) {
            if (origin.requesterUserId() != null) {
                return new ResolvedIdentity(String.valueOf(origin.requesterUserId()),
                        TRUST_AUTHENTICATED, "web");
            }
            String user = ToolExecutionContext.username(ctx);
            return user != null && !user.isBlank()
                    ? new ResolvedIdentity(user, TRUST_AUTHENTICATED, "web")
                    : ResolvedIdentity.NONE;
        }
        // webchat visitor ("api") or IM sender (feishu/wecom/…): external id,
        // no MateClaw account — forward with an explicit trust downgrade so the
        // backend knows it is NOT an authenticated MateClaw user.
        String requester = origin.requesterId();
        if (requester == null || requester.isBlank()) {
            return ResolvedIdentity.NONE;
        }
        String trust = "api".equals(channel) ? TRUST_ANONYMOUS : TRUST_EXTERNAL;
        return new ResolvedIdentity(requester, trust, channel);
    }

    /**
     * Resolve the identity injection for a call. Empty when the origin carries
     * no usable identity (cron / system / anonymous-without-id), or when token
     * mode is enabled but the key is unavailable (fail-closed).
     */
    public Optional<Injection> resolve(ToolContext ctx, String audience) {
        ResolvedIdentity id = classify(ctx);
        if (!id.present()) {
            return Optional.empty();
        }
        if (!properties.getToken().isEnabled()) {
            // Plaintext: prefix the value with the trust level so the backend
            // can tell authenticated from anonymous/external without a JWT.
            return Optional.of(new Injection(McpIdentityForwardProperties.USER_ARG,
                    id.trust() + ":" + id.subject()));
        }
        String jwt = mint(id, audience);
        if (jwt == null) {
            return Optional.empty();   // fail-closed: token mode but no key
        }
        return Optional.of(new Injection(McpIdentityForwardProperties.TOKEN_ARG, jwt));
    }

    /** Mint a short-lived RS256 JWT, or {@code null} if the key is unavailable. */
    private String mint(ResolvedIdentity id, String audience) {
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
                    .subject(id.subject())
                    .audience().add(audience).and()
                    .claim("trust", id.trust())
                    .claim("channel_type", id.channelType())
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
