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

    /**
     * Lazily parsed signing key; {@code null} until first successful parse or
     * while a parse is pending. Guarded by {@code this} (the parse block) and
     * safe to read outside the lock because the field is volatile and the
     * {@link PrivateKey} is published safely after construction.
     */
    private volatile PrivateKey signingKey;

    /**
     * PEM content last handed to the parser. Lets the cache self-heal when the
     * operator fixes the config (or a config reload pushes a new key) without an
     * app restart: if the PEM changed since the last attempt we retry instead of
     * sticking with a permanent null. {@code null} = never attempted.
     */
    private volatile String lastAttemptedPem;

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
     * <p>Trust is keyed off {@link ChatOrigin#channelType()}: only the explicit
     * {@code "web"} channel (built by {@code ChatOrigin.web()}) may resolve to
     * {@code authenticated} — every other channel is typed as {@code anonymous}
     * / {@code external}, and an <em>unknown</em> (null/blank) channel resolves
     * to {@link ResolvedIdentity#NONE} (fail-closed). The ThreadLocal
     * {@link ToolExecutionContext} username is consulted only inside the web
     * branch and only when no immutable user id is present.
     */
    ResolvedIdentity classify(ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        // Cron / system / unattributed: never forward identity.
        if (origin.cronOrigin() || "system".equals(origin.requesterId())) {
            return ResolvedIdentity.NONE;
        }
        String channel = origin.channelType();
        // Unknown / unattributed channel: never assert identity. An absent
        // channelType must NOT be promoted to authenticated — only the explicit
        // "web" channel (built by ChatOrigin.web()) carries MateClaw's assertion
        // that a real account backs this request. Treating null/blank as web
        // would silently stamp an untrusted ThreadLocal value with authenticated
        // trust, violating the fail-closed contract this service guarantees.
        if (channel == null || channel.isBlank()) {
            return ResolvedIdentity.NONE;
        }
        // Authenticated web account: MateClaw vouches for this user. Prefer the
        // immutable numeric id when available (web-console login); fall back to
        // the username when only the ThreadLocal path supplied identity.
        if ("web".equals(channel)) {
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

    /**
     * Resolve the signing key, parsing it lazily. Self-heals when the configured
     * PEM changes (e.g. an operator fixes a malformed key or a config reload
     * pushes a new one) — a prior failed parse is retried on the next call once
     * {@code private-key-pem} differs from what was last attempted, so recovery
     * no longer needs an app restart. Stays fail-closed otherwise.
     */
    private PrivateKey signingKey() {
        PrivateKey k = signingKey;
        if (k != null) {
            return k;
        }
        String pem = properties.getToken().getPrivateKeyPem();
        // Skip only while the PEM is unchanged since the last attempt — that
        // avoids re-parsing (and re-logging) on every call. A changed PEM clears
        // the way for a fresh parse, which is the self-healing path.
        if (lastAttemptedPem != null && lastAttemptedPem.equals(pem)) {
            return null;
        }
        synchronized (this) {
            if (signingKey != null) {
                return signingKey;
            }
            // Re-check under the lock: another thread may have just attempted
            // the same (unchanged) PEM.
            if (lastAttemptedPem != null && lastAttemptedPem.equals(pem)) {
                return null;
            }
            lastAttemptedPem = pem;
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
