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
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

        // ---- Branch coverage for the NONE returns inside classify() ----

        @Test
        @DisplayName("web channel, no requesterUserId, no ThreadLocal username → NONE")
        void webChannelNoUserNoThreadLocal() {
            // Do NOT seed the ThreadLocal — mirrors a thread that has never run a
            // tool-execution executor pass (or just cleared it). The web branch's
            // username fallback must then return NONE rather than inject garbage.
            ChatOrigin origin = ChatOrigin.web("c1", "", 1L, null, null);
            assertThat(svc(new McpIdentityForwardProperties()).classify(ctx(origin)))
                    .isEqualTo(McpIdentityForwardService.ResolvedIdentity.NONE);
        }

        @Test
        @DisplayName("non-web channel with blank requesterId → NONE")
        void nonWebChannelBlankRequester() {
            // A recognised channelType but no requester id: nothing to forward.
            ChatOrigin origin = new ChatOrigin(null, "c1", "", 1L, null,
                    null, null, false, null, "feishu", null, null, null);
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
        assertThat(service.resolve(ctx(origin), "my-api"))
                .as("malformed PEM ⇒ fail-closed, no token")
                .isEmpty();

        // 1b. The SAME malformed PEM, called again, must STILL return empty —
        //     proving the failure was cached (not retried per call) and that
        //     step 1 wasn't empty just because the PEM was treated as null.
        assertThat(service.resolve(ctx(origin), "my-api"))
                .as("repeated identical bad PEM ⇒ still fail-closed (cached failure)")
                .isEmpty();

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
    @DisplayName("signing key rotates when a valid PEM is replaced by another valid PEM (no restart)")
    void signingKeyRotatesOnValidPemChange() {
        // Regression for the rotation gap: once a key is cached, a subsequent
        // valid PEM change MUST take effect without an app restart — otherwise a
        // key retired on suspicion of compromise keeps signing tokens forever.
        KeyPair kp1 = rsaKeyPair();
        KeyPair kp2 = rsaKeyPair();
        String pem1 = Base64.getEncoder().encodeToString(kp1.getPrivate().getEncoded());
        String pem2 = Base64.getEncoder().encodeToString(kp2.getPrivate().getEncoded());
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);

        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);
        p.getToken().setIssuer("mateclaw");
        p.getToken().setTtlSeconds(60);
        p.getToken().setPrivateKeyPem(pem1);
        McpIdentityForwardService service = svc(p);

        // 1. First token is signed with key #1 and verifies with pubkey #1.
        String tok1 = service.resolve(ctx(origin), "my-api").orElseThrow().value();
        Jwts.parser().verifyWith(kp1.getPublic()).requireAudience("my-api").build()
                .parseSignedClaims(tok1).getPayload();

        // 2. Rotate to key #2. The next token MUST verify with pubkey #2 (not #1).
        p.getToken().setPrivateKeyPem(pem2);
        String tok2 = service.resolve(ctx(origin), "my-api").orElseThrow().value();
        Jwts.parser().verifyWith(kp2.getPublic()).requireAudience("my-api").build()
                .parseSignedClaims(tok2).getPayload();
        // And the rotated token must NOT verify with the OLD pubkey anymore.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                Jwts.parser().verifyWith(kp1.getPublic()).requireAudience("my-api").build()
                        .parseSignedClaims(tok2).getPayload());
    }

    @Test
    @DisplayName("signing-key parse is single-flight under concurrency (all tokens verify)")
    void signingKeyParseIsSingleFlightConcurrently() throws Exception {
        // Stress the double-checked locking: N threads hit a cold service at once.
        // Two properties must hold: (1) every returned token verifies, AND
        // (2) the key is parsed exactly once (single-flight) — otherwise the DCL
        // is meaningless. Counting the "loaded RS256 signing key" INFO log line
        // pins the single-flight guarantee the test is named for.
        // Guard the logback cast: these tests need a logback Logger to attach a
        // ListAppender. Skip (not fail) if the runtime SLF4J binding isn't logback.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.slf4j.LoggerFactory.getLogger(McpIdentityForwardService.class)
                        instanceof ch.qos.logback.classic.Logger,
                "logback must be the active SLF4J binding for this log-capture test");
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(McpIdentityForwardService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            KeyPair kp = rsaKeyPair();
            var p = new McpIdentityForwardProperties();
            p.getToken().setEnabled(true);
            p.getToken().setIssuer("mateclaw");
            p.getToken().setTtlSeconds(60);
            p.getToken().setPrivateKeyPem(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
            McpIdentityForwardService service = svc(p);

            int n = 16;
            java.util.List<java.util.concurrent.Callable<String>> tasks = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                tasks.add(() -> {
                    ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
                    return service.resolve(ctx(origin), "my-api").orElseThrow().value();
                });
            }
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                java.util.List<java.util.concurrent.Future<String>> futures = pool.invokeAll(tasks);
                pool.shutdown();
                org.assertj.core.api.Assertions.assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

                for (java.util.concurrent.Future<String> f : futures) {
                    String t = f.get();
                    assertThat(t).as("every concurrent call must yield a verifiable token").isNotBlank();
                    Jwts.parser().verifyWith(kp.getPublic()).requireAudience("my-api").build()
                            .parseSignedClaims(t).getPayload();
                }
            } finally {
                pool.shutdownNow();
            }

            long loads = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("loaded RS256 signing key"))
                    .count();
            assertThat(loads)
                    .as("16 concurrent cold-start calls must parse the key exactly once (single-flight DCL)")
                    .isEqualTo(1L);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("valid→invalid PEM rotation: keeps the stale key, does NOT re-parse/log on every call")
    void signingKeepsStaleKeyAndDedupsBadPem() {
        // Regression for R2-1: once a good key is cached, replacing the PEM with
        // a malformed value must (a) keep serving the old key (best-effort),
        // (b) log the parse failure ONCE, not on every subsequent call.
        KeyPair kp = rsaKeyPair();
        String goodPem = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);

        var p = new McpIdentityForwardProperties();
        p.getToken().setEnabled(true);
        p.getToken().setIssuer("mateclaw");
        p.getToken().setTtlSeconds(60);
        p.getToken().setPrivateKeyPem(goodPem);
        McpIdentityForwardService service = svc(p);

        // 1. Prime: a good key is loaded and a token mints.
        String tok1 = service.resolve(ctx(origin), "my-api").orElseThrow().value();
        Jwts.parser().verifyWith(kp.getPublic()).requireAudience("my-api").build()
                .parseSignedClaims(tok1).getPayload();

        // 2. Corrupt the PEM. Subsequent calls must still mint verifiable tokens
        //    (using the stale good key) — fail-soft, not fail-closed.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.slf4j.LoggerFactory.getLogger(McpIdentityForwardService.class)
                        instanceof ch.qos.logback.classic.Logger,
                "logback must be the active SLF4J binding for this log-capture test");
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(McpIdentityForwardService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            p.getToken().setPrivateKeyPem("not-a-valid-pem-anymore");
            for (int i = 0; i < 5; i++) {
                String t = service.resolve(ctx(origin), "my-api").orElseThrow().value();
                // Still verifies with the ORIGINAL pubkey → stale key still in use.
                Jwts.parser().verifyWith(kp.getPublic()).requireAudience("my-api").build()
                        .parseSignedClaims(t).getPayload();
            }
            long failures = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("failed to parse private-key-pem"))
                    .count();
            assertThat(failures)
                    .as("a repeated bad PEM must be parsed/logged exactly once, not once per call")
                    .isEqualTo(1L);
        } finally {
            logger.detachAppender(appender);
        }
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
        // exp must be bounded to the configured TTL window (60s) — not just any
        // future date. A regression that dropped ttlSeconds would leak here.
        assertThat(claims.getExpiration()).isAfter(new Date());
        assertThat(claims.getExpiration().toInstant())
                .as("exp must be within ttlSeconds (60) + small skew, not unbounded")
                .isBefore(Instant.now().plusSeconds(70));
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    @DisplayName("token mode: two mints for the same subject get distinct jti (replay distinguishability)")
    void tokenJtiUniqueAcrossMints() {
        // The backend may track consumed jtis to reject replays; that only works
        // if each mint produces a fresh jti.
        KeyPair kp = rsaKeyPair();
        var p = tokenProps(kp);
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        McpIdentityForwardService service = svc(p);

        Claims c1 = Jwts.parser().verifyWith(kp.getPublic()).build()
                .parseSignedClaims(service.resolve(ctx(origin), "my-api").orElseThrow().value())
                .getPayload();
        Claims c2 = Jwts.parser().verifyWith(kp.getPublic()).build()
                .parseSignedClaims(service.resolve(ctx(origin), "my-api").orElseThrow().value())
                .getPayload();

        assertThat(c1.getId()).as("jti must differ across mints").isNotEqualTo(c2.getId());
        assertThat(c1.getSubject()).isEqualTo(c2.getSubject());   // same subject, different token
    }

    @Test
    @DisplayName("plaintext mode: colon-bearing visitorId round-trips as anonymous:<id-with-colon>")
    void plaintextColonInSubject() {
        // webchat visitorIds may contain ':' (VISITOR_ID_PATTERN allows it). The
        // plaintext value is '<trust>:<subject>', so a colon in subject is only
        // recoverable via partition(":") (split on FIRST colon) — pin that contract.
        ChatOrigin origin = new ChatOrigin(null, "c1", "a:b", 1L, null,
                null, null, false, null, "api", null, null, null);
        Optional<McpIdentityForwardService.Injection> inj =
                svc(new McpIdentityForwardProperties()).resolve(ctx(origin), "my-api");
        assertThat(inj).isPresent();
        String value = inj.get().value();
        assertThat(value).startsWith("anonymous:");
        // partition(":") on the backend must recover the full colon-bearing subject.
        String subject = value.substring(value.indexOf(':') + 1);
        assertThat(subject).isEqualTo("a:b");
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
    @DisplayName("audienceFor: explicit mapping wins (by name AND by id), else server name/id")
    void audienceResolution() {
        var p = new McpIdentityForwardProperties();
        assertThat(p.audienceFor(42L, "svc")).isEqualTo("svc");          // default = name
        p.getToken().setAudiences(Map.of("svc", "https://api.internal"));
        assertThat(p.audienceFor(42L, "svc")).isEqualTo("https://api.internal");
        // Mapping by NUMERIC ID (as string) — branch not covered before.
        p.getToken().setAudiences(Map.of("42", "https://by-id.internal"));
        assertThat(p.audienceFor(42L, "unmapped-name"))
                .as("audience mapped by numeric id must win when name is unmapped")
                .isEqualTo("https://by-id.internal");
        // Name mapping takes precedence over id mapping when both are configured.
        p.getToken().setAudiences(Map.of("svc", "https://by-name", "42", "https://by-id"));
        assertThat(p.audienceFor(42L, "svc")).isEqualTo("https://by-name");
    }
}
