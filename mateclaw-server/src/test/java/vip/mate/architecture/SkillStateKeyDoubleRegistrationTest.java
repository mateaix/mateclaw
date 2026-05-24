package vip.mate.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Strict double-registration check for the skill progressive-disclosure state
 * keys.
 *
 * <p>{@link StateKeyRegistrationCoverageTest} only verifies that a key appears
 * somewhere in {@code AgentGraphBuilder.java}; it cannot tell apart the ReAct
 * and Plan-Execute {@code KeyStrategyFactory} blocks. The {@code CHAT_ORIGIN}
 * regression showed that "registered once" is not enough — multi-node merges
 * silently drop keys when one graph's factory leaves them out.
 *
 * <p>{@code LOADED_SKILLS} is written via read-merge-write in ActionNode, so a
 * dropped key would silently disable the load_skill catalog pin. It must appear
 * in BOTH factory blocks.
 */
class SkillStateKeyDoubleRegistrationTest {

    private static final String[] SKILL_KEYS = {
            "LOADED_SKILLS",
            "ENABLED_EXTENSION_TOOLS",
    };

    @Test
    void everySkillDisclosureKeyMustAppearAtLeastTwiceInAddStrategyCalls() throws Exception {
        Path src = Paths.get("src/main/java/vip/mate/agent/AgentGraphBuilder.java")
                .toAbsolutePath();
        if (!Files.exists(src)) {
            fail("Cannot find AgentGraphBuilder.java at " + src);
        }
        String content = Files.readString(src);

        for (String key : SKILL_KEYS) {
            Pattern p = Pattern.compile(
                    "\\.addStrategy\\(\\s*MateClawStateKeys\\." + key + "\\b");
            Matcher m = p.matcher(content);
            int count = 0;
            while (m.find()) count++;
            if (count < 2) {
                fail("Skill disclosure state key " + key + " must be registered in BOTH the "
                        + "ReAct and Plan-Execute KeyStrategyFactory blocks "
                        + "(found " + count + " addStrategy occurrence(s) in "
                        + "AgentGraphBuilder.java). Without double registration the "
                        + "spring-ai-alibaba-graph merge can drop the key, silently "
                        + "disabling the load_skill catalog pin.");
            }
            assertTrue(count >= 2,
                    "Sanity: " + key + " should have >=2 addStrategy calls");
        }
    }
}
