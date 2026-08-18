package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningNodePromptLanguageTest {

    @Test
    @DisplayName("runtime prompt constrains visible thinking to the user's language")
    void groundedPromptIncludesVisibleThinkingLanguageRule() {
        String prompt = ReasoningNode.buildGroundedSystemPrompt("基础提示", false);

        assertTrue(prompt.contains("可见思考"));
        assertTrue(prompt.contains("用户语言"));
        assertTrue(prompt.contains("简体中文"));
    }

    @Test
    @DisplayName("empty-completion nudge is Chinese so it does not bias thinking into English")
    void emptyCompletionNudgeIsChinese() throws Exception {
        Field field = ReasoningNode.class.getDeclaredField("EMPTY_COMPLETION_NUDGE");
        field.setAccessible(true);
        String nudge = (String) field.get(null);

        assertFalse(nudge.contains("Your previous turn was empty"));
        assertFalse(nudge.contains("continue now"));
        assertTrue(nudge.contains("上一轮"));
        assertTrue(nudge.contains("调用工具"));
    }
}
