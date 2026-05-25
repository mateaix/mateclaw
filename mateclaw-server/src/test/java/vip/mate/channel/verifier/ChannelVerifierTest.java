package vip.mate.channel.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the wizard preflight path. We only exercise the
 * fail-fast branches that need no network (missing credentials), since
 * the happy paths require live upstream services. End-to-end coverage
 * happens in the nightly integration job described in RFC-084 §8.
 */
class ChannelVerifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resultHelpers_buildExpectedShapes() {
        VerificationResult ok = VerificationResult.ok(42, "hi", Map.of("a", 1));
        assertTrue(ok.ok());
        assertFalse(ok.skipped());
        assertEquals("hi", ok.headline());
        assertEquals(1, ok.identity().get("a"));

        VerificationResult bad = VerificationResult.failed(7, "nope", "token", "fix it");
        assertFalse(bad.ok());
        assertEquals("token", bad.invalidField());
        assertEquals("fix it", bad.hint());
        assertTrue(bad.identity().isEmpty());

        VerificationResult skipped = VerificationResult.skipped("nothing to verify");
        assertTrue(skipped.ok());
        assertTrue(skipped.skipped());
    }

    @Test
    void telegramVerifier_failsFast_withoutToken() {
        TelegramVerifier verifier = new TelegramVerifier(objectMapper);
        VerificationResult r = verifier.verify(new VerificationRequest(
                "telegram", Collections.emptyMap(), 1L));
        assertFalse(r.ok());
        assertEquals("bot_token", r.invalidField());
        // Should not reach the network — duration is 0.
        assertEquals(0, r.durationMs());
    }

    @Test
    void discordVerifier_failsFast_withoutToken() {
        DiscordVerifier verifier = new DiscordVerifier(objectMapper);
        VerificationResult r = verifier.verify(new VerificationRequest(
                "discord", Collections.emptyMap(), 1L));
        assertFalse(r.ok());
        assertEquals("bot_token", r.invalidField());
        assertEquals(0, r.durationMs());
    }

    @Test
    void slackVerifier_failsFast_withoutToken() {
        SlackVerifier verifier = new SlackVerifier();
        VerificationResult r = verifier.verify(new VerificationRequest(
                "slack", Collections.emptyMap(), 1L));
        assertFalse(r.ok());
        assertEquals("bot_token", r.invalidField());
    }

    @Test
    void wecomVerifier_failsFast_withoutCredentials() {
        WeComVerifier verifier = new WeComVerifier(objectMapper);
        VerificationResult missingBoth = verifier.verify(new VerificationRequest(
                "wecom", Collections.emptyMap(), 1L));
        assertFalse(missingBoth.ok());
        assertEquals("bot_id", missingBoth.invalidField());
        assertEquals(0, missingBoth.durationMs());

        VerificationResult missingSecret = verifier.verify(new VerificationRequest(
                "wecom", Map.of("bot_id", "bot_xxxxx"), 1L));
        assertFalse(missingSecret.ok());
        assertEquals("secret", missingSecret.invalidField());
    }

    @Test
    void feishuVerifier_failsFast_withoutCredentials() {
        FeishuVerifier verifier = new FeishuVerifier(objectMapper);
        VerificationResult missingAppId = verifier.verify(new VerificationRequest(
                "feishu", Collections.emptyMap(), 1L));
        assertFalse(missingAppId.ok());
        assertEquals("app_id", missingAppId.invalidField());

        VerificationResult missingSecret = verifier.verify(new VerificationRequest(
                "feishu", Map.of("app_id", "cli_xxxxx"), 1L));
        assertFalse(missingSecret.ok());
        assertEquals("app_secret", missingSecret.invalidField());
    }

    @Test
    void dingtalkVerifier_failsFast_withoutCredentials() {
        DingTalkVerifier verifier = new DingTalkVerifier(objectMapper);
        VerificationResult missingClientId = verifier.verify(new VerificationRequest(
                "dingtalk", Collections.emptyMap(), 1L));
        assertFalse(missingClientId.ok());
        assertEquals("client_id", missingClientId.invalidField());

        VerificationResult missingSecret = verifier.verify(new VerificationRequest(
                "dingtalk", Map.of("client_id", "dingxxxxxxxx"), 1L));
        assertFalse(missingSecret.ok());
        assertEquals("client_secret", missingSecret.invalidField());
    }

    @Test
    void weixinVerifier_failsFast_withoutToken() {
        WeixinVerifier verifier = new WeixinVerifier(objectMapper);
        VerificationResult r = verifier.verify(new VerificationRequest(
                "weixin", Collections.emptyMap(), 1L));
        assertFalse(r.ok());
        assertEquals("bot_token", r.invalidField());
    }

    @Test
    void registry_indexesByChannelType_andLetsLastWin() {
        TelegramVerifier first = new TelegramVerifier(objectMapper);
        TelegramVerifier second = new TelegramVerifier(objectMapper);
        ChannelVerifierRegistry registry = new ChannelVerifierRegistry(java.util.List.of(first, second));
        registry.index();
        assertTrue(registry.find("telegram").isPresent());
        assertSame(second, registry.find("telegram").orElseThrow());
        assertTrue(registry.find("nonexistent").isEmpty());
    }
}
