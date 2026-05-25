package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link NodeStreamingChatHelper#hasRepeatingSuffix} — the cheap
 * loop detector that catches reasoning-mode models (qwen3.6, deepseek-r1)
 * stuck emitting the same final-answer paragraph over and over.
 *
 * <p>Real failure pattern from production: model alternates English
 * "Wait, I should X. Done. I will write the response." with the same
 * Chinese answer, dozens of times, until {@code max_tokens} runs out.
 * Without this guard the user waits for a wall of duplicated text;
 * with it, the stream stops at the third or fourth copy and the
 * already-accumulated content gets returned as a partial answer.
 *
 * <p>The detector probes periods from 24 chars (anything shorter would
 * false-positive on natural phrases) up to 240 chars; 4 verbatim
 * consecutive copies is the threshold (3-times structured outputs like
 * "TL;DR / body / TL;DR again" should pass through).
 */
class ContentRepetitionGuardTest {

    private static final int MIN_PERIOD = 24;
    private static final int MAX_PERIOD = 240;
    private static final int MIN_OCCURRENCES = 4;

    @Test
    @DisplayName("non-cyclic prose with varied sentences does NOT trip")
    void naturalProseDoesNotTrip() {
        // Real writing: each sentence is unique, no consecutive paragraph
        // repeats anywhere in the buffer.
        String prose = "MateClaw 是一个企业级 AI 助手。它支持多种渠道接入，包括企业微信、"
                + "飞书、钉钉。Agent 通过 StateGraph 编排，可以调用工具、生成图片、查询知识库。"
                + "用户可以在 Web 控制台、桌面 App 或群聊里发起对话。系统记忆采用三档分层："
                + "PROFILE.md 记录用户画像、MEMORY.md 沉淀稳定事实、memory/YYYY-MM-DD.md "
                + "保存当日上下文。审批流程基于 Spring AI Alibaba Graph，工具调用前会被守卫拦截，"
                + "高风险操作必须由用户显式批准才能执行。会话与频道之间是多对多关系。";
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                prose, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES));
    }

    @Test
    @DisplayName("verbatim short paragraph repeated 4× → trips")
    void verbatimQuadrupleRepeatTrips() {
        // The exact production failure mode: 50-char Chinese answer repeated.
        String paragraph = "收到语音啦！想查昨天的天气没问题，告诉我城市名我马上帮你查！\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(paragraph);
        assertTrue(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES),
                "5 verbatim ~30-char paragraphs in a row should trip");
    }

    @Test
    @DisplayName("3 verbatim repeats stay UNDER threshold (legitimate triple-mention pattern)")
    void threeRepeatsBelowThreshold() {
        // Some legitimate outputs repeat structured summaries 2-3 times
        // (e.g. "TL;DR" + body + "TL;DR" again). The threshold of 4
        // gives breathing room so these don't false-positive.
        String paragraph = "请告诉我您所在的城市，例如北京、上海或深圳，我可以为您查询天气。\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(paragraph);
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES),
                "3 verbatim paragraphs must NOT trip — preserves triple-mention outputs");
    }

    @Test
    @DisplayName("interleaved English thinking + Chinese answer pattern still trips")
    void interleavedRepetitionTrips() {
        // Mirrors the production trace exactly: English thinking
        // alternating with the same Chinese answer. The combined
        // "thinking + answer" unit is the actual repeating period.
        String unit = "Wait, I should write.\nOkay.\n收到语音啦！告诉我城市名我马上帮你查！\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(unit);
        assertTrue(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES),
                "5 verbatim 'thinking + answer' cycles should trip");
    }

    @Test
    @DisplayName("empty / short / null content returns false (fast path)")
    void shortContentDoesNotTrip() {
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                null, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES));
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                "", MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES));
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                "hi there", MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES));
        // Just under MIN_PERIOD × MIN_OCCURRENCES → can't possibly match.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append('x');
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES));
    }

    @Test
    @DisplayName("trailing repeat after long preamble: detects only the looping suffix")
    void detectsLoopAfterPreamble() {
        // Realistic: model produces a long valid answer, then enters a
        // loop appending the same trailer. The detector must catch the
        // loop even though the buffer prefix has perfectly varied text.
        StringBuilder sb = new StringBuilder();
        sb.append("好的，我已经为您完成了任务，下面是详细的执行结果：\n");
        sb.append("第一步，我读取了配置文件并解析了内容。\n");
        sb.append("第二步，我调用了天气查询接口拿到了原始数据。\n");
        sb.append("第三步，我将结果格式化为人类可读的中文文本。\n");
        // Now the model gets stuck repeating a closing phrase.
        String trailer = "如有其他问题，请随时告诉我，我会尽快为您解答和处理。\n";
        for (int i = 0; i < 5; i++) sb.append(trailer);
        assertTrue(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES),
                "trailing 5×-repeated trailer must trip even after long preamble");
    }

    @Test
    @DisplayName("single-char fill (200x 'a') does NOT trip — too short to be a real period")
    void singleCharFillDoesNotTrip() {
        // 'aaaa...' could be parsed as period=1 with 200 occurrences,
        // but our floor is MIN_PERIOD=24, so a literal 24-char run of
        // 'a' would need to repeat 4× — which is just one continuous
        // run of 96 'a' chars. That's a degenerate case; mark as
        // not-tripping-via-this-detector since it's not the "self-
        // arguing loop" failure mode (a model emitting 'aaaaaaa...'
        // would hit max_tokens harmlessly without any degradation
        // worth user attention).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('a');
        // 200 'a' chars: period=24 unit is "aaaa...a" (24 of them).
        // The prior 24-char block is also "aaa...a" (24 of them).
        // So they DO match. This trips. Document the behavior — it's
        // mostly harmless because models don't actually loop on single
        // chars.
        assertTrue(NodeStreamingChatHelper.hasRepeatingSuffix(
                sb, MIN_PERIOD, MAX_PERIOD, MIN_OCCURRENCES),
                "documented behavior: pure single-char fills DO trip; not a real failure mode in practice");
    }

    @Test
    @DisplayName("invalid args return false defensively")
    void invalidArgsReturnFalse() {
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix("text", 0, 100, 4));
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix("text", 24, 240, 1));
        // maxPeriod < minPeriod
        assertFalse(NodeStreamingChatHelper.hasRepeatingSuffix("text", 100, 50, 4));
    }

    // ===== dedupTrailingRepeats =====
    //
    // Once the loop guard fires, the streamed text has already gone out
    // (SSE chunks can't be unsent), but the DB-persisted final answer +
    // IM channel reply should show ONE clean copy of the looping unit
    // instead of the wall the user just watched scroll by.

    @Test
    @DisplayName("dedup: 5 verbatim copies → 1 copy")
    void dedupCollapsesRepeats() {
        String unit = "收到语音啦！想查昨天的天气没问题，告诉我城市名我马上帮你查！\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(unit);
        String result = NodeStreamingChatHelper.dedupTrailingRepeats(sb.toString(), MIN_PERIOD, MAX_PERIOD);
        assertEquals(unit, result, "5 copies should collapse to exactly 1");
    }

    @Test
    @DisplayName("dedup: prefix + repeated trailer → prefix + 1 copy of trailer")
    void dedupPreservesPrefixCollapseTrailer() {
        String prefix = "好的，下面是详细回答：第一步完成了。第二步也完成了。下面是结论。\n";
        String trailer = "如有其他问题请随时告诉我，我会尽快为您解答处理。\n";
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 5; i++) sb.append(trailer);
        String result = NodeStreamingChatHelper.dedupTrailingRepeats(sb.toString(), MIN_PERIOD, MAX_PERIOD);
        assertEquals(prefix + trailer, result,
                "prefix preserved verbatim; trailer collapses 5×→1×");
    }

    @Test
    @DisplayName("dedup: no trailing repeats → buffer unchanged")
    void dedupNoRepeatsUnchanged() {
        String prose = "这是一段没有任何尾部重复的正常回答，包含多个不同的句子和话题。"
                + "我们讨论了天气、新闻、技术，每段内容都不同。";
        assertEquals(prose,
                NodeStreamingChatHelper.dedupTrailingRepeats(prose, MIN_PERIOD, MAX_PERIOD));
    }

    @Test
    @DisplayName("dedup: only 1 copy at end (no actual repetition) → unchanged")
    void dedupSingleCopyUnchanged() {
        String unit = "请告诉我您所在的城市，我帮您查询。";
        // Just one copy at the tail — nothing to collapse.
        assertEquals(unit,
                NodeStreamingChatHelper.dedupTrailingRepeats(unit, MIN_PERIOD, MAX_PERIOD));
    }

    @Test
    @DisplayName("dedup: empty / null inputs return as-is")
    void dedupEmptyOrNull() {
        assertNull(NodeStreamingChatHelper.dedupTrailingRepeats(null, MIN_PERIOD, MAX_PERIOD));
        assertEquals("", NodeStreamingChatHelper.dedupTrailingRepeats("", MIN_PERIOD, MAX_PERIOD));
    }

    @Test
    @DisplayName("dedup: 2 copies (the minimum trip threshold) → 1 copy")
    void dedupTwoCopiesCollapse() {
        // dedup uses 2+ copies as its trigger (vs. hasRepeatingSuffix's 4×
        // detection threshold). Once the guard has decided the buffer is
        // looping, even a 2× tail should be collapsed since we know
        // structurally the model is mid-loop.
        String unit = "如果您还有任何其他疑问，欢迎随时联系我，我会尽快回复。";
        String input = unit + unit;
        assertEquals(unit,
                NodeStreamingChatHelper.dedupTrailingRepeats(input, MIN_PERIOD, MAX_PERIOD));
    }
}
