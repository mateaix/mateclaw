package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.AgentToolSet;
import vip.mate.wiki.service.WikiContextService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: wiki / runtime-context content must survive a
 * {@code prompt_too_long} retry.
 * <p>
 * An earlier PTL retry path in {@code ReasoningNode} reassembled the prompt
 * with only the system prompt + workspace runtime context — the wiki
 * relevant snippet that the initial assembly injected was silently dropped,
 * so the retried prompt asked the model the same question with strictly
 * less context. {@code buildNonHistoryPrefix(...)} now centralises the
 * three-layer prefix so the initial assembly and the retry path consume the
 * same returned list. This test pins that contract: the prefix list
 * contains all three layers and the wiki layer reflects what
 * {@link WikiContextService#buildRelevantContext} returned.
 */
class ReasoningNodePtlPromptTest {

    private static final String WIKI_RELEVANT_TEXT =
            "[Wiki Relevant Pages]\n- module-X.md (matches 'investigate'): ...";

    @Test
    void prefixIncludesSystemRuntimeAndWikiSegments() {
        WikiContextService wikiContextService = mock(WikiContextService.class);
        when(wikiContextService.buildRelevantContext(eq(42L), anyString()))
                .thenReturn(WIKI_RELEVANT_TEXT);

        ReasoningNode node = newNode(wikiContextService);

        List<Message> prefix = node.buildNonHistoryPrefix(
                "you are a helpful assistant",
                "/workspace/active",
                "42",
                "investigate the bug in module X",
                vip.mate.agent.context.ChatOrigin.EMPTY);

        // Three layers: System, runtime-context UserMessage, wiki UserMessage.
        assertThat(prefix).hasSize(3);
        assertThat(prefix.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prefix.get(0).getText()).contains("you are a helpful assistant");
        assertThat(prefix.get(1)).isInstanceOf(UserMessage.class);
        assertThat(prefix.get(2)).isInstanceOf(UserMessage.class);
        assertThat(prefix.get(2).getText()).isEqualTo(WIKI_RELEVANT_TEXT);
    }

    @Test
    void buildIsDeterministicAcrossCalls_soInitialAndRetryShareIdenticalLayout() {
        // Critical regression invariant: both Prompt assemblies (initial and
        // PTL retry) consume the SAME list reference, so the wiki segment
        // can never diverge between them. Belt-and-suspenders, also verify
        // that two independent calls with the same inputs produce
        // structurally identical output.
        WikiContextService wikiContextService = mock(WikiContextService.class);
        when(wikiContextService.buildRelevantContext(eq(42L), anyString()))
                .thenReturn(WIKI_RELEVANT_TEXT);

        ReasoningNode node = newNode(wikiContextService);

        List<Message> a = node.buildNonHistoryPrefix(
                "sys", "/workspace", "42", "goal",
                vip.mate.agent.context.ChatOrigin.EMPTY);
        List<Message> b = node.buildNonHistoryPrefix(
                "sys", "/workspace", "42", "goal",
                vip.mate.agent.context.ChatOrigin.EMPTY);

        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).getClass()).isEqualTo(b.get(i).getClass());
            assertThat(a.get(i).getText()).isEqualTo(b.get(i).getText());
        }
    }

    @Test
    void noWikiServiceWiredSkipsWikiSegment() {
        // wikiContextService is optional — when null (e.g. minimal config or
        // a test rig), the prefix should still be valid: just system +
        // runtime context, no wiki layer.
        ReasoningNode node = newNode(null);

        List<Message> prefix = node.buildNonHistoryPrefix(
                "you are a helpful assistant",
                "/workspace/active",
                "42",
                "investigate the bug in module X",
                vip.mate.agent.context.ChatOrigin.EMPTY);

        assertThat(prefix).hasSize(2);
        assertThat(prefix.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prefix.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void nonNumericAgentIdSkipsWikiSegment() {
        WikiContextService wikiContextService = mock(WikiContextService.class);
        ReasoningNode node = newNode(wikiContextService);

        List<Message> prefix = node.buildNonHistoryPrefix(
                "sys", "/workspace", "not-a-number", "goal",
                vip.mate.agent.context.ChatOrigin.EMPTY);

        // Non-numeric agentId is the contract carried over from the
        // pre-refactor codebase — skip wiki injection rather than throwing.
        assertThat(prefix).hasSize(2);
        verify(wikiContextService,
                org.mockito.Mockito.never()).buildRelevantContext(
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void blankWikiResultSkipsWikiSegment() {
        WikiContextService wikiContextService = mock(WikiContextService.class);
        when(wikiContextService.buildRelevantContext(eq(42L), anyString()))
                .thenReturn("   ");  // blank → drop the layer

        ReasoningNode node = newNode(wikiContextService);

        List<Message> prefix = node.buildNonHistoryPrefix(
                "sys", "/workspace", "42", "goal",
                vip.mate.agent.context.ChatOrigin.EMPTY);

        assertThat(prefix).hasSize(2);
    }

    private static ReasoningNode newNode(WikiContextService wikiContextService) {
        // 9-arg constructor — explicit supportsReasoningEffort + empty
        // tool set, nulls for the streaming / conversation-window deps we
        // don't exercise here.
        AgentToolSet emptyTools = AgentToolSet.fromCallbacks(List.of(), List.of());
        return new ReasoningNode(
                /* chatModel */ null,
                /* toolSet */ emptyTools,
                /* reasoningEffort */ null,
                /* supportsReasoningEffort */ false,
                /* streamingHelper */ null,
                /* conversationWindowManager */ null,
                /* streamTracker */ null,
                /* maxOutputTokens */ 1024,
                wikiContextService);
    }
}
