package vip.mate.architecture;

import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Architecture guard — every state key declared on
 * {@link MateClawStateKeys} that participates in graph state (i.e. is not a
 * node-name constant) MUST be registered in
 * {@link vip.mate.agent.AgentGraphBuilder}'s {@code KeyStrategyFactory}
 * for at least one of the two graphs (ReAct + Plan-Execute).
 *
 * <p>Regression rationale: the post-deploy bug where {@code CHAT_ORIGIN} was
 * declared on {@link MateClawStateKeys} but missing from both
 * {@code KeyStrategyFactory} blocks shipped silently, and {@code spring-ai-alibaba-graph}
 * dropped the key on multi-node merges, causing the channel-binding flakiness
 * reported by the user. This test parses the source of
 * {@code AgentGraphBuilder.java} for all
 * {@code .addStrategy(MateClawStateKeys.X, ...)} mentions and asserts the
 * coverage so the same kind of "forgot to register" can never ship again.
 *
 * <p>Excluded by suffix: any constant whose name ends with {@code _NODE} —
 * those are graph-node identifiers used by {@code addNode(...)}, not state
 * keys.
 */
class StateKeyRegistrationCoverageTest {

    private static final Pattern ADD_STRATEGY = Pattern.compile(
            "\\.addStrategy\\(\\s*MateClawStateKeys\\.([A-Z_]+)");

    @Test
    void everyStateKeyMustBeRegisteredInKeyStrategyFactory() throws Exception {
        // Read the AgentGraphBuilder source — relative to mateclaw-server module root.
        Path source = Paths.get("src/main/java/vip/mate/agent/AgentGraphBuilder.java")
                .toAbsolutePath();
        if (!Files.exists(source)) {
            fail("Cannot find AgentGraphBuilder.java at " + source
                    + " — has the file moved? Update this test's path.");
        }
        String content = Files.readString(source);

        Set<String> registered = new TreeSet<>();
        Matcher m = ADD_STRATEGY.matcher(content);
        while (m.find()) {
            registered.add(m.group(1));
        }
        assertTrue(registered.size() > 10,
                "Suspiciously few addStrategy hits — regex broken? Found: " + registered);

        Set<String> declared = new TreeSet<>();
        for (var f : MateClawStateKeys.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods)
                    || !Modifier.isFinal(mods) || f.getType() != String.class) {
                continue;
            }
            // Node-name constants are NOT state keys — they're graph-node
            // identifiers used by addNode(...). Exclude them by suffix.
            if (f.getName().endsWith("_NODE")) continue;
            declared.add(f.getName());
        }

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(registered);

        if (!missing.isEmpty()) {
            fail("State keys declared on MateClawStateKeys but NOT registered in any "
                    + "KeyStrategyFactory in AgentGraphBuilder.java:\n"
                    + "  " + missing + "\n\n"
                    + "Without registration, spring-ai-alibaba-graph may drop these keys on "
                    + "multi-node state merges (silently, intermittently). Add an "
                    + ".addStrategy(MateClawStateKeys.X, KeyStrategy.REPLACE) line for each "
                    + "missing key in BOTH the ReAct and Plan-Execute KeyStrategyFactory blocks "
                    + "(or document why the key is intentionally Plan-only / ReAct-only).");
        }
    }
}
