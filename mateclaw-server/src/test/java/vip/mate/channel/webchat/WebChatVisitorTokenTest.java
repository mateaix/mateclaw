package vip.mate.channel.webchat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR #297 P1 IDOR 修复回归测试：list/messages/delete 端点的鉴权不能再只靠调用方自报的 visitorId，
 * 必须验证服务端用密钥签发的 visitor token。这里覆盖 token 的签发/校验语义。
 */
class WebChatVisitorTokenTest {

    private static final String SECRET = "test-secret-do-not-use-in-prod";
    private static final Long CHANNEL = 7L;
    private static final String VISITOR = "visitor-abc";

    // ==================== 签发 ====================

    @Test
    void token_isDeterministic_forSameInputs() {
        assertEquals(
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR),
                WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR));
    }

    @Test
    void token_differsPerVisitor() {
        assertNotEquals(
                WebChatController.computeVisitorToken(SECRET, CHANNEL, "alice"),
                WebChatController.computeVisitorToken(SECRET, CHANNEL, "bob"));
    }

    @Test
    void token_isChannelBound_notPortable() {
        // 同一 visitorId 在不同渠道下 token 不同 → 持 A 渠道 token 不能操作 B 渠道同名 visitor。
        assertNotEquals(
                WebChatController.computeVisitorToken(SECRET, 1L, VISITOR),
                WebChatController.computeVisitorToken(SECRET, 2L, VISITOR));
    }

    @Test
    void token_dependsOnSecret() {
        assertNotEquals(
                WebChatController.computeVisitorToken("secret-a", CHANNEL, VISITOR),
                WebChatController.computeVisitorToken("secret-b", CHANNEL, VISITOR));
    }

    // ==================== 校验 ====================

    @Test
    void verify_acceptsTokenIssuedForSameVisitor() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR);
        assertTrue(WebChatController.verifyVisitorToken(SECRET, CHANNEL, VISITOR, token));
    }

    @Test
    void verify_rejectsForgedVisitorIdWithoutToken() {
        // 攻击者持公开 key，传受害者 visitorId，但拿不到对应 token。
        assertFalse(WebChatController.verifyVisitorToken(SECRET, CHANNEL, "victim", null));
        assertFalse(WebChatController.verifyVisitorToken(SECRET, CHANNEL, "victim", ""));
    }

    @Test
    void verify_rejectsTokenMintedForAnotherVisitor() {
        // 攻击者拿自己 visitor 的合法 token，去操作受害者 visitor → 必须失败。
        String attackerToken = WebChatController.computeVisitorToken(SECRET, CHANNEL, "attacker");
        assertFalse(WebChatController.verifyVisitorToken(SECRET, CHANNEL, "victim", attackerToken));
    }

    @Test
    void verify_rejectsTokenFromAnotherChannel() {
        String tokenForChannel1 = WebChatController.computeVisitorToken(SECRET, 1L, VISITOR);
        assertFalse(WebChatController.verifyVisitorToken(SECRET, 2L, VISITOR, tokenForChannel1));
    }

    @Test
    void verify_rejectsTamperedToken() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertFalse(WebChatController.verifyVisitorToken(SECRET, CHANNEL, VISITOR, tampered));
    }

    @Test
    void verify_rejectsNullChannelOrVisitor() {
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL, VISITOR);
        assertFalse(WebChatController.verifyVisitorToken(SECRET, null, VISITOR, token));
        assertFalse(WebChatController.verifyVisitorToken(SECRET, CHANNEL, null, token));
    }
}
