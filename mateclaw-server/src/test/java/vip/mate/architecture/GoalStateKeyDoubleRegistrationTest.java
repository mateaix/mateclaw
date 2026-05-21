package vip.mate.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Strict double-registration check for the persistent-goal state keys.
 *
 * <p>{@link StateKeyRegistrationCoverageTest} only verifies that a key
 * appears somewhere in {@code AgentGraphBuilder.java}; it cannot tell
 * apart the ReAct and Plan-Execute {@code KeyStrategyFactory} blocks.
 * History (the {@code CHAT_ORIGIN} regression) shows that "registered
 * once" is not enough — multi-node merges silently drop keys when one
 * graph's factory leaves them out.
 *
 * <p>Each of the five Goal state keys must therefore appear at least
 * twice in the builder source: once per graph. This test pins that
 * invariant so future regressions get caught at PR time.
 */
class GoalStateKeyDoubleRegistrationTest {

    private static final String[] GOAL_KEYS = {
            "ACTIVE_GOAL",
            "GOAL_EVALUATION_RESULT",
            "GOAL_FOLLOWUP_INJECTED",
            "GOAL_FOLLOWUP_PROMPT",
            "GOAL_EVALUATED_THIS_RUN",
    };

    @Test
    void everyGoalKeyMustAppearAtLeastTwiceInAddStrategyCalls() throws Exception {
        Path src = Paths.get("src/main/java/vip/mate/agent/AgentGraphBuilder.java")
                .toAbsolutePath();
        if (!Files.exists(src)) {
            fail("Cannot find AgentGraphBuilder.java at " + src);
        }
        String content = Files.readString(src);

        for (String key : GOAL_KEYS) {
            Pattern p = Pattern.compile(
                    "\\.addStrategy\\(\\s*MateClawStateKeys\\." + key + "\\b");
            Matcher m = p.matcher(content);
            int count = 0;
            while (m.find()) count++;
            if (count < 2) {
                fail("Goal state key " + key + " must be registered in BOTH the "
                        + "ReAct and Plan-Execute KeyStrategyFactory blocks "
                        + "(found " + count + " addStrategy occurrence(s) in "
                        + "AgentGraphBuilder.java). The architecture coverage "
                        + "test only checks 'appears somewhere'; this test "
                        + "is the strict double-registration guard documented "
                        + "in RFC 48 §3.2 v2.");
            }
            assertTrue(count >= 2,
                    "Sanity: " + key + " should have >=2 addStrategy calls");
        }
    }

    @Test
    void goalEvaluationNodeIdentifierAppearsExactlyOncePerGraph() throws Exception {
        Path src = Paths.get("src/main/java/vip/mate/agent/AgentGraphBuilder.java")
                .toAbsolutePath();
        String content = Files.readString(src);
        Pattern p = Pattern.compile(
                "\\.addNode\\(\\s*MateClawStateKeys\\.GOAL_EVALUATION_NODE\\b");
        Matcher m = p.matcher(content);
        int count = 0;
        while (m.find()) count++;
        assertEquals(2, count,
                "GOAL_EVALUATION_NODE must be added as a node in BOTH graphs " +
                        "(ReAct + Plan-Execute). Found " + count + " addNode calls.");
    }
}
