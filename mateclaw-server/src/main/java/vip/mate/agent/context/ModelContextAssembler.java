package vip.mate.agent.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Single budgeting boundary for reasoning and recovery prompts. Protected prefix blocks are
 * never trimmed; history selection and tool-pair integrity remain owned by LoopMessageBudgeter.
 * This class does not invoke a model or execute tools.
 */
public final class ModelContextAssembler {
    private static final int KEEP_RECENT_TOOL_RESPONSES = 3;
    private final LoopMessageBudgeter budgeter = new LoopMessageBudgeter();

    public record Assembly(Prompt prompt, List<Message> history,
                           LoopMessageBudgeter.BudgetTrace trace, int reservedTokens,
                           long estimatedTokens, boolean overflow, boolean mandatoryOverflow) {
        /** Summarization may reduce content without changing the number of messages. */
        public boolean reduces(Assembly original) {
            return estimatedTokens < original.estimatedTokens;
        }
    }

    public List<Message> prepareHistory(List<Message> history, ConversationWindowManager manager,
                                       String conversationId, String workspaceBasePath) {
        if (manager == null) return history;
        List<Message> aged = manager.compactAgedToolResponses(history, KEEP_RECENT_TOOL_RESPONSES);
        return manager.pruneOldToolResultsForModelInput(aged, conversationId, workspaceBasePath);
    }

    public Assembly assemble(List<Message> prefix, List<Message> history, ChatOptions options,
                             int contextWindowTokens, int outputReserveTokens) {
        int reserved = Math.toIntExact(PromptBudget.estimateTokens(
                new Prompt(prefix, options), null, outputReserveTokens));
        int protectedTokens = protectedHistoryTokens(history);
        LoopBudgetConfig config = LoopBudgetConfig.forContext(contextWindowTokens)
                .withReservedPrefixTokens(reserved)
                .withAvailableHistoryTokens(Math.max(0, contextWindowTokens - reserved - protectedTokens));
        var budgeted = budgeter.budget(history, config);
        return finish(prefix, budgeted.messages(), options, contextWindowTokens,
                outputReserveTokens, reserved, budgeted.trace());
    }

    private Assembly finish(List<Message> prefix, List<Message> history, ChatOptions options,
                            int contextWindowTokens, int outputReserveTokens, int reserved,
                            LoopMessageBudgeter.BudgetTrace trace) {
        List<Message> selected = List.copyOf(history);
        List<Message> instructions = new ArrayList<>(prefix);
        instructions.addAll(selected);
        Prompt prompt = new Prompt(instructions, options);
        long estimate = PromptBudget.estimateTokens(prompt, null, outputReserveTokens);
        return new Assembly(prompt, selected, trace, reserved, estimate,
                estimate > contextWindowTokens,
                (long) reserved + protectedHistoryTokens(selected) > contextWindowTokens);
    }

    public Assembly assembleRetry(List<Message> prefix, List<Message> compacted,
                                  UserMessage originalGoal, ChatOptions options,
                                  int contextWindowTokens, int outputReserveTokens) {
        List<Message> restored = new ArrayList<>(compacted);
        if (originalGoal != null && restored.stream().noneMatch(message -> message instanceof UserMessage
                && message.getText().equals(originalGoal.getText()))) {
            int insertion = 0;
            for (int i = 0; i < restored.size(); i++) {
                if (restored.get(i) instanceof UserMessage) insertion = i + 1;
            }
            while (insertion < restored.size() && restored.get(insertion) instanceof SystemMessage) insertion++;
            restored.add(insertion, originalGoal);
        }
        // A structured summary is already a deliberate compression result. Running the tail
        // selector again could erase it, especially when the summary is a UserMessage.
        // Preserve it intact and let the caller reject an oversized assembled request.
        int reserved = Math.toIntExact(PromptBudget.estimateTokens(
                new Prompt(prefix, options), null, outputReserveTokens));
        var trace = LoopMessageBudgeter.BudgetTrace.untouched(restored.size(),
                TokenEstimator.estimateTokens(restored), reserved, 0);
        return finish(prefix, restored, options, contextWindowTokens, outputReserveTokens, reserved, trace);
    }

    public static UserMessage latestUser(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage user) return user;
        }
        return null;
    }

    private static int protectedHistoryTokens(List<Message> history) {
        int tokens = 0;
        for (Message message : history) {
            if (!(message instanceof SystemMessage)) break;
            tokens += TokenEstimator.estimateTokens(message);
        }
        return tokens + TokenEstimator.estimateTokens(latestUser(history));
    }
}
