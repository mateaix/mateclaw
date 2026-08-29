package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepExecutionSkillCatalogTest {

    @Test
    @SuppressWarnings("unchecked")
    void stepMessagesRenderSkillCatalogWithSkillsLoadedThisRun() throws Exception {
        AtomicReference<Set<String>> seenLoaded = new AtomicReference<>();
        StepExecutionNode node = new StepExecutionNode(
                null, null, null, null, null, null, null, null,
                loaded -> {
                    seenLoaded.set(loaded);
                    return "## Skills\n- docx";
                },
                1_000L);

        Method method = StepExecutionNode.class.getDeclaredMethod(
                "buildStepMessages",
                PlanStateAccessor.class, String.class, String.class,
                String.class, String.class, String.class);
        method.setAccessible(true);

        List<Message> messages = (List<Message>) method.invoke(
                node,
                accessor(Set.of("docx")),
                "生成 Word 文档",
                "system",
                "/tmp/workspace",
                "qwen",
                "dashscope");

        assertEquals(Set.of("docx"), seenLoaded.get());
        assertTrue(messages.stream().anyMatch(m -> m.getText().contains("## Skills")));
    }

    private static PlanStateAccessor accessor(Set<String> loadedSkills) {
        Map<String, Object> values = new HashMap<>();
        values.put(PlanStateKeys.GOAL, "生成文档");
        values.put(PlanStateKeys.PLAN_STEPS, new ArrayList<>(List.of("生成 Word 文档")));
        values.put(PlanStateKeys.CURRENT_STEP_INDEX, 0);
        values.put(PlanStateKeys.COMPLETED_RESULTS, new ArrayList<String>());
        values.put(PlanStateKeys.WORKING_CONTEXT, "");
        values.put(MateClawStateKeys.LOADED_SKILLS, loadedSkills);
        return new PlanStateAccessor(new OverAllState(values));
    }
}
