package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ActionNode#extractLoadedSkillNames} — the load_skill
 * detection that feeds the {@code LOADED_SKILLS} catalog pin.
 */
class ActionNodeLoadSkillTest {

    private static AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, args);
    }

    @Test
    @DisplayName("empty / null batch yields no names")
    void emptyBatch() {
        assertTrue(ActionNode.extractLoadedSkillNames(null).isEmpty());
        assertTrue(ActionNode.extractLoadedSkillNames(List.of()).isEmpty());
    }

    @Test
    @DisplayName("non-load_skill calls are ignored")
    void nonLoadSkillIgnored() {
        List<AssistantMessage.ToolCall> calls = List.of(
                call("web_search", "{\"query\":\"x\"}"),
                call("read_file", "{\"path\":\"/tmp/a\"}"));
        assertTrue(ActionNode.extractLoadedSkillNames(calls).isEmpty());
    }

    @Test
    @DisplayName("load_skill skillName arg is extracted")
    void extractsSkillName() {
        List<AssistantMessage.ToolCall> calls = List.of(
                call("load_skill", "{\"skillName\":\"pdf\"}"));
        assertEquals(Set.of("pdf"), ActionNode.extractLoadedSkillNames(calls));
    }

    @Test
    @DisplayName("multiple load_skill calls collect every name, order preserved")
    void multipleLoads() {
        List<AssistantMessage.ToolCall> calls = List.of(
                call("load_skill", "{\"skillName\":\"pdf\"}"),
                call("web_search", "{\"query\":\"x\"}"),
                call("load_skill", "{\"skillName\":\"docx\",\"filePath\":\"references/a.md\"}"));
        assertEquals(Set.of("pdf", "docx"), ActionNode.extractLoadedSkillNames(calls));
    }

    @Test
    @DisplayName("alternate arg keys skill_name / name are accepted")
    void alternateKeys() {
        assertEquals(Set.of("alpha"),
                ActionNode.extractLoadedSkillNames(List.of(call("load_skill", "{\"skill_name\":\"alpha\"}"))));
        assertEquals(Set.of("beta"),
                ActionNode.extractLoadedSkillNames(List.of(call("load_skill", "{\"name\":\"beta\"}"))));
    }

    @Test
    @DisplayName("malformed or empty args are skipped without throwing")
    void malformedArgsSkipped() {
        List<AssistantMessage.ToolCall> calls = List.of(
                call("load_skill", "not-json"),
                call("load_skill", ""),
                call("load_skill", "{\"skillName\":\"\"}"),
                call("load_skill", "{\"other\":\"y\"}"));
        assertTrue(ActionNode.extractLoadedSkillNames(calls).isEmpty());
    }

    @Test
    @DisplayName("enable_tool toolName arg is extracted; non-enable_tool ignored")
    void extractsEnabledToolNames() {
        List<AssistantMessage.ToolCall> calls = List.of(
                call("enable_tool", "{\"toolName\":\"image_generate\"}"),
                call("web_search", "{\"query\":\"x\"}"),
                call("enable_tool", "{\"tool_name\":\"music_generate\"}"));
        assertEquals(Set.of("image_generate", "music_generate"),
                ActionNode.extractEnabledToolNames(calls));
    }

    @Test
    @DisplayName("enable_tool detection ignores empty batch and load_skill calls")
    void enableToolEmptyAndCrossTalk() {
        assertTrue(ActionNode.extractEnabledToolNames(null).isEmpty());
        assertTrue(ActionNode.extractEnabledToolNames(
                List.of(call("load_skill", "{\"skillName\":\"pdf\"}"))).isEmpty());
    }
}
