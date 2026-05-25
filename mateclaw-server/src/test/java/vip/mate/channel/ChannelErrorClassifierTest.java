package vip.mate.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the error-detection heuristic so a future tweak in
 * {@code NodeStreamingChatHelper} that renames an error template doesn't
 * silently regress IM channels back into the self-replicating 400 loop.
 */
class ChannelErrorClassifierTest {

    private final ChannelErrorClassifier classifier = new ChannelErrorClassifier();

    @Test
    void normal_reply_is_not_error() {
        assertFalse(classifier.isErrorReply("好的，我已经为您完成了任务。"));
        assertFalse(classifier.isErrorReply(""));
        assertFalse(classifier.isErrorReply(null));
        assertFalse(classifier.isErrorReply("⏰ 定时任务已就绪：每天 00:18"));
    }

    @Test
    void error_prefix_is_detected() {
        assertTrue(classifier.isErrorReply("[错误] Bad request: Bad request, please check input"));
        assertTrue(classifier.isErrorReply("[错误] 工具调用失败"));
    }

    @Test
    void error_substrings_emitted_by_NodeStreamingChatHelper_are_detected() {
        // Mirrors templates in NodeStreamingChatHelper.buildErrorResultWithType
        assertTrue(classifier.isErrorReply("Bad request: invalid_request_error"));
        assertTrue(classifier.isErrorReply("LLM 调用失败: connection reset"));
        assertTrue(classifier.isErrorReply("LLM 调用超时"));
        assertTrue(classifier.isErrorReply("LLM 调用被中断"));
        assertTrue(classifier.isErrorReply("Prompt 过长: token limit exceeded"));
        assertTrue(classifier.isErrorReply("认证失败: 401 Unauthorized"));
        assertTrue(classifier.isErrorReply("LLM 返回空响应"));
    }

    @Test
    void status_for_maps_correctly() {
        assertEquals("error", classifier.statusFor("[错误] Bad request"));
        assertEquals("completed", classifier.statusFor("Hello world"));
        assertEquals("completed", classifier.statusFor(""));
    }

    @Test
    void aicard_partial_with_error_prefix_is_detected() {
        // The DingTalk AICard catch path now wraps partial output with a
        // [错误] prefix; verify the classifier catches that compound shape.
        String reply = "[错误] AI Card streaming failed: timeout\n\n（已生成的部分内容，已忽略）\n部分回答 ...";
        assertTrue(classifier.isErrorReply(reply));
    }
}
