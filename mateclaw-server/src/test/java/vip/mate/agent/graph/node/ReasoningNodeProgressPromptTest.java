package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningNodeProgressPromptTest {

    @Test
    @DisplayName("progress registration guidance respects the executor batch cap")
    void progressPromptCapsRegistrationBatches() throws Exception {
        Field field = ReasoningNode.class.getDeclaredField("TOOL_USE_ENFORCEMENT");
        field.setAccessible(true);
        String prompt = (String) field.get(null);

        assertThat(prompt).contains("每批最多 16 个");
        assertThat(prompt).doesNotContain("一条回复里 N 个 `progress_update` 同时发出");
    }
}
