package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModelContextAssemblerTest {
    private final ModelContextAssembler assembler = new ModelContextAssembler();
    private final ChatOptions options = ChatOptions.builder().maxTokens(512).build();

    @Test
    void accountsForPrefixSchemasAndOutputExactlyOnce() {
        ToolCallback tool = mock(ToolCallback.class);
        var definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("lookup").description("lookup record").inputSchema("{\"type\":\"object\"}").build();
        when(tool.getToolDefinition()).thenReturn(definition);
        ChatOptions tools = ToolCallingChatOptions.builder().maxTokens(512).toolCallbacks(tool).build();
        List<Message> prefix = List.of(new SystemMessage("skill constraints; successful action receipts"));
        List<Message> history = List.of(new UserMessage("original objective"));
        var result = assembler.assemble(prefix, history, tools, 8192, 512);
        assertThat(result.prompt().getInstructions()).containsExactlyElementsOf(
                List.of(prefix.getFirst(), history.getFirst()));
        assertThat(result.estimatedTokens()).isEqualTo(PromptBudget.estimateTokens(result.prompt(), null, 512));
        assertThat(result.reservedTokens()).isEqualTo(TokenEstimator.estimateTokens(prefix)
                + TokenEstimator.estimateToolsTokens(List.of(tool)) + 512);
        assertThat(result.overflow()).isFalse();
    }

    @Test
    void retryRestoresOriginalObjectiveAndKeepsAllProtectedPrefixBlocks() {
        UserMessage objective = new UserMessage("finish original objective");
        List<Message> prefix = List.of(new SystemMessage("skill constraints"), new SystemMessage("execution receipts"));
        var result = assembler.assembleRetry(prefix, List.of(new SystemMessage("summary"),
                new AssistantMessage("working")), objective, options, 8192, 512);
        assertThat(result.history()).contains(objective);
        assertThat(result.prompt().getInstructions()).startsWith(prefix.toArray(Message[]::new));
        var repeated = assembler.assembleRetry(prefix, result.history(), objective, options, 8192, 512);
        assertThat(repeated.history().stream().filter(objective::equals).count()).isEqualTo(1);
    }

    @Test
    void rejectsImpossibleWindowWithoutDeletingGoalOrSplittingLatestToolPair() {
        UserMessage goal = new UserMessage("preserve goal");
        var call = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("c1", "function", "lookup", "{}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "lookup", "result".repeat(500)))).build();
        var result = assembler.assemble(List.of(new SystemMessage("mandatory".repeat(1000))),
                List.of(goal, call, response), options, 1024, 512);
        assertThat(result.overflow()).isTrue();
        assertThat(result.history()).contains(goal, call, response);
        assertThat(result.mandatoryOverflow()).isTrue();
    }

    @Test
    void smallerRetryWithSameMessageCountIsUseful() {
        UserMessage goal = new UserMessage("original goal");
        var original = assembler.assemble(List.of(), List.of(goal,
                new AssistantMessage("large history ".repeat(100))), options, 32000, 512);
        var retry = assembler.assembleRetry(List.of(), List.of(goal,
                new AssistantMessage("summary")), goal, options, 32000, 512);
        assertThat(retry.history()).hasSameSizeAs(original.history());
        assertThat(retry.reduces(original)).isTrue();
        assertThat(original.reduces(retry)).isFalse();
    }

    @Test
    void retryDoesNotDiscardFreshSummaryEvenWhenLoopTailPolicyWouldTrimIt() {
        UserMessage goal = new UserMessage("original objective");
        UserMessage summary = new UserMessage("completed actions and decisions ".repeat(100));
        List<Message> compacted = new ArrayList<>();
        compacted.add(summary);
        compacted.add(goal);
        for (int i = 0; i < 5; i++) compacted.add(new AssistantMessage("recent ".repeat(60)));
        var retry = assembler.assembleRetry(List.of(new SystemMessage("prefix".repeat(600))),
                compacted, goal, options, 4096, 512);
        assertThat(retry.overflow()).isFalse();
        assertThat(retry.history()).containsExactlyElementsOf(compacted);
    }

    @Test
    void oversizedRetryRetainsSummaryAndReportsOverflowInsteadOfDiscardingIt() {
        UserMessage goal = new UserMessage("original objective");
        UserMessage summary = new UserMessage("界".repeat(10000));
        var retry = assembler.assembleRetry(List.of(), List.of(summary), goal, options, 4096, 512);
        assertThat(retry.history()).contains(summary, goal);
        assertThat(retry.overflow()).isTrue();
    }

    @Test
    void preparationReusesAgeAndSpillPoliciesWithoutMutatingInput() {
        ConversationWindowManager manager = mock(ConversationWindowManager.class);
        List<Message> original = new ArrayList<>(List.of(new UserMessage("goal")));
        List<Message> aged = List.of(new UserMessage("aged"));
        List<Message> pruned = List.of(new UserMessage("pruned"));
        when(manager.compactAgedToolResponses(original, 3)).thenReturn(aged);
        when(manager.pruneOldToolResultsForModelInput(aged, "conv", "/workspace")).thenReturn(pruned);
        assertThat(assembler.prepareHistory(original, manager, "conv", "/workspace")).isEqualTo(pruned);
        assertThat(original).hasSize(1);
        assertThat(assembler.prepareHistory(original, null, "conv", "/workspace")).isEqualTo(original);
    }
}
