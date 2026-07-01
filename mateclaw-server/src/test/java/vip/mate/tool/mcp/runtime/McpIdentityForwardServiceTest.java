package vip.mate.tool.mcp.runtime;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link McpIdentityForwardService}: identity typing across channels,
 * plaintext vs signed-token resolution, and fail-closed behaviour.
 *
 * <p>The core of this suite is the {@code trust}/{@code channel_type} typing —
 * making sure an authenticated MateClaw user, a webchat visitor, an IM sender,
 * and a cron run are forwarded with distinguishable, non-fabricated identities
 * (see RFC: on-behalf-of identity typing, issue #459 review).
 */
class McpIdentityForwardServiceTest {

    @AfterEach
    void clear() {
        ToolExecutionContext.clear();
    }

    private McpIdentityForwardService svc(McpIdentityForwardProperties p) {
        return new McpIdentityForwardService(p);
    }

    /** Build a ToolContext carrying the given origin, mirroring ToolExecutionExecutor. */
    private static ToolContext ctx(ChatOrigin origin) {
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    private static KeyPair rsaKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").genKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token-mode config signed with {@code kp}'s private key. */
    private static McpIdentityForwardProperties tokenProps(KeyPair kp) {
        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);
        p.getToken().setIssuer("mateclaw");
        p.getToken().setTtlSeconds(60);
        p.getToken().setPrivateKeyPem(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        return p;
    }

    // ==================== Identity typing across channels ====================

    @Nested
    @DisplayName("classify: identity typing per channel")
    class Classify {

        @Test
        @DisplayName("authenticated web user → sub=userId, trust=authenticated")
        void authenticatedWebUser() {
            ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
            McpIdentityForwardService.ResolvedIdentity id = svc(new McpIdentityForwardProperties()).classify(ctx(origin));
            assertThat(id.subject()).isEqualTo("42");
            assertThat(id.trust()).isEqualTo(McpIdentityForwardService.TRUST_AUTHENTICATED);
            assertThat(id.channelType()).isEqualTo("web");
        }

        @Test
        @DisplayName("webchat visitor (channelType=api) → trust=anonymous, sub=visitorId")
        void webchatVisitor() {
            // webchat sets requesterId=visitorId and channelType=api via withSender
            ChatOrigin origin = ChatOrigin.web("c1", "visitor-xyz", 1L, null)
                    .withSender(null, "api", null);
            McpIdentityForwardService.ResolvedIdentity id = svc(new McpIdentityForwardProperties()).classify(ctx(origin));
            assertThat(id.subject()).isEqualTo("visitor-xyz");
            assertThat(id.trust()).isEqualTo(McpIdentityForwardService.TRUST_ANONYMOUS);
            assertThat(id.channelType()).isEqualTo("api");
        }

        @Test
        @DisplayName("IM sender (channelType=feishu) → trust=external")
        void imSender() {
            ChatOrigin origin = new ChatOrigin(null, "c1", "im_user_1", 1L, null,
                    9L, null, false, "张三", "feishu", "grp1", null, null);
            McpIdentityForwardService.ResolvedIdentity id = svc(new McpIdentityForwardProperties()).classify(ctx(origin));
            assertThat(id.subject()).isEqualTo("im_user_1");
            assertThat(id.trust()).isEqualTo(McpIdentityForwardService.TRUST_EXTERNAL);
            assertThat(id.channelType()).isEqualTo("feishu");
        }

        @Test
        @DisplayName("cron origin → NONE (never assert identity for a non-user)")
        void cronOrigin() {
            ChatOrigin origin = ChatOrigin.cron("c1", 1L, null, 9L, null);
            assertThat(svc(new McpIdentityForwardProperties()).classify(ctx(origin)))
                    .isEqualTo(McpIdentityForwardService.ResolvedIdentity.NONE);
        }

        @Test
        @DisplayName("system requesterId → NONE")
        void systemRequester() {
            // An origin whose requesterId is "system" but cronOrigin=false (defensive)
            ChatOrigin origin = new ChatOrigin(null, "c1", "system", 1L, null,
                    null, null, false, null, null, null, null, null);
            assertThat(svc(new McpIdentityForwardProperties()).classify(ctx(origin)))
                    .isEqualTo(McpIdentityForwardService.ResolvedIdentity.NONE);
        }

        @Test
        @DisplayName("web origin without userId falls back to ThreadLocal username (legacy path)")
        void webOriginLegacyThreadLocal() {
            ToolExecutionContext.set("c1", "alice");
            // 5-arg web(): no requesterUserId → falls back to ThreadLocal
            ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null);
            McpIdentityForwardService.ResolvedIdentity id = svc(new McpIdentityForwardProperties()).classify(ctx(origin));
            assertThat(id.subject()).isEqualTo("alice");
            assertThat(id.trust()).isEqualTo(McpIdentityForwardService.TRUST_AUTHENTICATED);
        }

        // ---- Fail-closed: an unknown / unattributed channel must NEVER be
        //      promoted to authenticated, even when a stale ThreadLocal username
        //      is present. This is the regression for the privilege-escalation
        //      gap where channel==null used to fall into the web branch and
        //      stamp an untrusted identifier with authenticated trust. ----

        @Test
        @DisplayName("unknown channelType (null) → NONE, even with a polluted ThreadLocal (no privilege escalation)")
        void unknownChannelIsFailClosedEvenWithThreadLocal() {
            // A stale username from a prior request reused this thread.
            ToolExecutionContext.set("c1", "attacker");
            // System-style origin built with no channelType (e.g. SkillConsolidation
            // / SkillReflection internal tasks): requesterId="", channelType=null.
            ChatOrigin origin = new ChatOrigin(null, "c1", "", 1L, null,
                    null, null, false, null, null, null, null, null);
            assertThat(svc(new McpIdentityForwardProperties()).classify(ctx(origin)))
                    .isEqualTo(McpIdentityForwardService.ResolvedIdentity.NONE);
        }

        @Test
        @DisplayName("blank channelType → NONE (fail-closed, not authenticated)")
        void blankChannelIsFailClosed() {
            ToolExecutionContext.set("c1", "attacker");
            ChatOrigin origin = new ChatOrigin(null, "c1", "someone", 1L, null,
                    null, null, false, null, "  ", null, null, null);
            assertThat(svc(new McpIdentityForwardProperties()).classify(ctx(origin)))
                    .isEqualTo(McpIdentityForwardService.ResolvedIdentity.NONE);
        }

        @Test
        @DisplayName("unrecognised non-web channel → downgraded to external (never authenticated)")
        void novelChannelIsDowngradedNotAuthenticated() {
            // A channel the classifier doesn't recognise is treated as external
            // (explicit trust downgrade), never as authenticated. The backend sees
            // an untrusted id and decides for itself — this is the key guarantee:
            // no unknown channel can ever acquire authenticated trust.
            ChatOrigin origin = new ChatOrigin(null, "c1", "u1", 1L, null,
                    null, null, false, null, "future-net", null, null, null);
            McpIdentityForwardService.ResolvedIdentity id =
                    svc(new McpIdentityForwardProperties()).classify(ctx(origin));
            assertThat(id.subject()).isEqualTo("u1");
            assertThat(id.trust()).isEqualTo(McpIdentityForwardService.TRUST_EXTERNAL);
            assertThat(id.channelType()).isEqualTo("future-net");
        }
    }

    // ==================== Resolution: plaintext & token modes ====================

    @Test
    @DisplayName("plaintext mode: injects '<trust>:<subject>' under USER_ARG")
    void plaintextTyped() {
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        var p = new McpIdentityForwardProperties();
        Optional<McpIdentityForwardService.Injection> inj = svc(p).resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();
        assertThat(inj.get().key()).isEqualTo(McpIdentityForwardProperties.USER_ARG);
        assertThat(inj.get().value()).isEqualTo("authenticated:42");
    }

    @Test
    @DisplayName("plaintext mode: anonymous visitor carries trust=anonymous prefix")
    void plaintextAnonymous() {
        ChatOrigin origin = ChatOrigin.web("c1", "visitor-xyz", 1L, null).withSender(null, "api", null);
        Optional<McpIdentityForwardService.Injection> inj =
                svc(new McpIdentityForwardProperties()).resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();
        assertThat(inj.get().value()).startsWith("anonymous:visitor-xyz");
    }

    @Test
    @DisplayName("no usable identity (cron): nothing injected")
    void noIdentity() {
        ChatOrigin origin = ChatOrigin.cron("c1", 1L, null, 9L, null);
        assertThat(svc(new McpIdentityForwardProperties()).resolve(ctx(origin), "my-api")).isEmpty();
    }

    @Test
    @DisplayName("token mode but no key: fail-closed (nothing injected)")
    void tokenModeNoKey() {
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);   // no private-key-pem
        assertThat(svc(p).resolve(ctx(origin), "my-api")).isEmpty();
    }

    @Test
    @DisplayName("signing key self-heals after a bad PEM is corrected (no restart needed)")
    void signingKeySelfHealsAfterConfigFix() {
        KeyPair kp = rsaKeyPair();
        String goodPem = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);

        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);
        p.getToken().setIssuer("mateclaw");
        p.getToken().setTtlSeconds(60);

        // 1. Malformed key → fail-closed.
        p.getToken().setPrivateKeyPem("not-a-valid-pem");
        McpIdentityForwardService service = svc(p);
        assertThat(service.resolve(ctx(origin), "my-api")).isEmpty();

        // 2. Operator fixes the config (or a reload pushes a good key) → next
        //    call re-parses and issues a token, without needing an app restart.
        p.getToken().setPrivateKeyPem(goodPem);
        Optional<McpIdentityForwardService.Injection> inj = service.resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();
        assertThat(inj.get().key()).isEqualTo(McpIdentityForwardProperties.TOKEN_ARG);

        // The minted token verifies against the matching public key.
        Claims claims = Jwts.parser()
                .verifyWith(kp.getPublic())
                .requireIssuer("mateclaw")
                .requireAudience("my-api")
                .build()
                .parseSignedClaims(inj.get().value())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo("42");
    }

    @Test
    @DisplayName("token mode: mints RS256 JWT that verifies with trust/channel_type claims")
    void tokenMintAndVerify() throws Exception {
        KeyPair kp = rsaKeyPair();
        var p = tokenProps(kp);
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);

        Optional<McpIdentityForwardService.Injection> inj = svc(p).resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();
        assertThat(inj.get().key()).isEqualTo(McpIdentityForwardProperties.TOKEN_ARG);

        // The REST backend verifies with the public key and reads the typed claims.
        Claims claims = Jwts.parser()
                .verifyWith(kp.getPublic())
                .requireIssuer("mateclaw")
                .requireAudience("my-api")
                .build()
                .parseSignedClaims(inj.get().value())
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("trust", String.class)).isEqualTo(McpIdentityForwardService.TRUST_AUTHENTICATED);
        assertThat(claims.get("channel_type", String.class)).isEqualTo("web");
        assertThat(claims.getExpiration()).isAfter(new Date());
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    @DisplayName("token mode: anonymous visitor token carries trust=anonymous")
    void tokenAnonymousVisitor() throws Exception {
        KeyPair kp = rsaKeyPair();
        var p = tokenProps(kp);
        ChatOrigin origin = ChatOrigin.web("c1", "visitor-xyz", 1L, null).withSender(null, "api", null);

        Optional<McpIdentityForwardService.Injection> inj = svc(p).resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();

        Claims claims = Jwts.parser()
                .verifyWith(kp.getPublic())
                .build()
                .parseSignedClaims(inj.get().value())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo("visitor-xyz");
        assertThat(claims.get("trust", String.class)).isEqualTo(McpIdentityForwardService.TRUST_ANONYMOUS);
        assertThat(claims.get("channel_type", String.class)).isEqualTo("api");
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
