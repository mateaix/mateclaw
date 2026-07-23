package vip.mate.channel.webchat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR #297 P1 IDOR 修复回归测试：list/messages/delete 端点的鉴权不能再只靠调用方自报的 visitorId，
 * 必须验证服务端用密钥签发的 visitor token。这里覆盖 token 的签发/校验语义。
 * <p>注:V148 之后 token 形态变成 {@code <base64sig>.<expEpochSec>},exp 参与签名;
 * 撤销表查询由实例 {@link WebChatController#verifyVisitorToken} 接入,签名 + 过期
 * 部分通过 {@link WebChatController#verifyVisitorTokenSignature} static 暴露给单测。
 */
class WebChatVisitorTokenTest {

    private static final String SECRET = "test-secret-do-not-use-in-prod";
    private static final Long CHANNEL = 7L;
    private static final String VISITOR = "visitor-abc";
    private static final long FAR_FUTURE = Instant.now().getEpochSecond() + 3_600L;

    // ==================== 签发 ====================

    @Test
    void token_isDeterministic_forSameInputs() {
        assertEquals(
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE),
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE));
    }

    @Test
    void token_differsPerVisitor() {
        assertNotEquals(
                WebChatController.computeVisitorToken(SECRET, CHANNEL, "alice", FAR_FUTURE),
                WebChatController.computeVisitorToken(SECRET, CHANNEL, "bob", FAR_FUTURE));
    }

    @Test
    void token_isChannelBound_notPortable() {
        // 同一 visitorId 在不同渠道下 token 不同 → 持 A 渠道 token 不能操作 B 渠道同名 visitor。
        assertNotEquals(
                WebChatController.computeVisitorToken(SECRET, 1L, VISITOR, FAR_FUTURE),
                WebChatController.computeVisitorToken(SECRET, 2L, VISITOR, FAR_FUTURE));
    }

    @Test
    void token_dependsOnSecret() {
        assertNotEquals(
                WebChatController.computeVisitorToken("secret-a", CHANNEL, VISITOR, FAR_FUTURE),
                WebChatController.computeVisitorToken("secret-b", CHANNEL, VISITOR, FAR_FUTURE));
    }

    @Test
    void token_differsWhenExpirationDiffers() {
        // Two tokens for the same (channel, visitor) but different exp must differ —
        // otherwise a leaked old token could be replayed past its expiry.
        assertNotEquals(
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE),
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE + 60));
    }

    // ==================== 校验(签名 + 过期) ====================

    @Test
    void verify_acceptsTokenIssuedForSameVisitor() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE);
        assertTrue(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, VISITOR, token));
    }

    @Test
    void verify_rejectsForgedVisitorIdWithoutToken() {
        // 攻击者持公开 key，传受害者 visitorId，但拿不到对应 token。
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, "victim", null));
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, "victim", ""));
    }

    @Test
    void verify_rejectsTokenMintedForAnotherVisitor() {
        // 攻击者拿自己 visitor 的合法 token，去操作受害者 visitor → 必须失败。
        String attackerToken = WebChatController.computeVisitorToken(SECRET, CHANNEL, "attacker", FAR_FUTURE);
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, "victim", attackerToken));
    }

    @Test
    void verify_rejectsTokenFromAnotherChannel() {
        String tokenForChannel1 = WebChatController.computeVisitorToken(SECRET, 1L, VISITOR, FAR_FUTURE);
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, 2L, VISITOR, tokenForChannel1));
    }

    @Test
    void verify_rejectsTamperedToken() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, VISITOR, tampered));
    }

    @Test
    void verify_rejectsNullChannelOrVisitor() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE);
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, null, VISITOR, token));
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, null, token));
    }

    @Test
    void verify_rejectsExpiredToken() {
        long past = Instant.now().getEpochSecond() - 60;
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, past);
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, VISITOR, token));
    }

    @Test
    void verify_rejectsTamperedExpiration() {
        // Attacker takes a valid token and bumps the exp — but exp participates in
        // the HMAC, so the signature no longer matches.
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR, FAR_FUTURE);
        int dot = token.lastIndexOf('.');
        String tampered = token.substring(0, dot + 1) + (FAR_FUTURE + 3_600);
        assertFalse(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL, VISITOR, tampered));
    }

    // ============ conversationId / username 边界（避免溢出 VARCHAR(64) → /stream 500）============

    @Test
    void deriveConversationId_staysWithin64_forLongInputs() {
        String id = WebChatController.deriveConversationId(9147001L, "v".repeat(120), "s".repeat(64));
        assertTrue(id.length() <= 64, "conversationId must fit VARCHAR(64), got " + id.length());
        // a legitimate 64-char sessionId alone already overflows the old scheme
        String id2 = WebChatController.deriveConversationId(9147001L, "alice", "s".repeat(64));
        assertTrue(id2.length() <= 64, "conversationId must fit VARCHAR(64), got " + id2.length());
    }

    @Test
    void deriveConversationId_unchanged_forShortInputs() {
        assertEquals("webchat:9147001:alice:s1",
                WebChatController.deriveConversationId(9147001L, "alice", "s1"));
        assertEquals("webchat:9147001:alice",
                WebChatController.deriveConversationId(9147001L, "alice", null));
    }

    // ===== #558: channel isolation — the channelId token must drive the prefix =====

    @Test
    void deriveConversationId_isChannelScoped_notApiKeyScoped() {
        // The #558 bug: two channels whose apiKeys share a prefix (all generated keys
        // start with "mc_webchat_") derived the SAME conversationId for one visitor,
        // so sessions collided across channels. The channelId token must differ.
        String chanA = WebChatController.deriveConversationId(1L, "alice", "s1");
        String chanB = WebChatController.deriveConversationId(2L, "alice", "s1");
        assertNotEquals(chanA, chanB, "different channels must derive different ids");
        assertEquals("webchat:1:alice:s1", chanA);
        assertEquals("webchat:2:alice:s1", chanB);
    }

    @Test
    void webchatUsername_staysWithin64_forLongVisitor() {
        assertTrue(WebChatController.webchatUsername("v".repeat(120)).length() <= 64);
        assertEquals("webchat:alice", WebChatController.webchatUsername("alice"));
    }
}
