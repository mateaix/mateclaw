package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the static "## About You" identity block appended to every agent's
 * system prompt. This is the cache-stable half of the self-introspection
 * feature — it answers "who are you / what are you based on", while the
 * volatile model line lives in RuntimeContextInjector.
 */
class AgentGraphBuilderIdentityBlockTest {

    @Test
    @DisplayName("identity block names MateClaw and the core tech stack")
    void identityBlockMentionsPlatformAndStack() {
        String block = AgentGraphBuilder.ABOUT_YOU_BLOCK;

        assertTrue(block.contains("## About You"), "missing heading: " + block);
        assertTrue(block.contains("MateClaw"), "must name the platform: " + block);
        assertTrue(block.contains("Spring AI Alibaba Graph"), "must name the graph runtime: " + block);
    }
}
