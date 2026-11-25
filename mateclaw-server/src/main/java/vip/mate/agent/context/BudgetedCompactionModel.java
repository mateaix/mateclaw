package vip.mate.agent.context;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.llm.chatmodel.ChatModelDefaults;
import java.util.concurrent.CancellationException;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** Guards and counts actual compaction-model invocations, preserving a caller-reserved business call. */
public final class BudgetedCompactionModel implements ChatModel {
    public static final class CallBudgetExceeded extends RuntimeException {}
    public static final class SummaryPromptOverflow extends RuntimeException {}
    private final ChatModel delegate;
    private final long allowedCalls;
    private final int windowTokens;
    private final int fallbackOutputTokens;
    private volatile CancellationException cancellation;
    private volatile String blockedPrompt;
    private int calls;
    private boolean callBudgetBlocked;
    private final java.util.Map<String,Integer> usage = new java.util.LinkedHashMap<>();
    public CancellationException cancellation() { return cancellation; }
    public String blockedPrompt() { return blockedPrompt; }
    public boolean callBudgetBlocked() { return callBudgetBlocked; }
    public synchronized java.util.Map<String,Integer> usage() { return java.util.Map.copyOf(usage); }
    public RunState withUsage(RunState state) {
        var values = new java.util.LinkedHashMap<>(state.data());
        usage().forEach((key,value) -> values.put(key,Math.addExact(state.value(key,0),value)));
        return new RunState(values);
    }
    private synchronized void recordUsage(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) return;
        var value=response.getMetadata().getUsage();
        addUsage(PROMPT_TOKENS,value.getPromptTokens());addUsage(COMPLETION_TOKENS,value.getCompletionTokens());
        var nativeTokens=vip.mate.llm.cache.CacheUsageExtractor.extract(value);
        addUsage(CACHE_READ_TOKENS,value.getCacheReadInputTokens()!=null ? Math.toIntExact(value.getCacheReadInputTokens()) : nativeTokens.cacheReadTokens());
        addUsage(CACHE_WRITE_TOKENS,value.getCacheWriteInputTokens()!=null ? Math.toIntExact(value.getCacheWriteInputTokens()) : nativeTokens.cacheWriteTokens());
        addUsage(REASONING_TOKENS,nativeTokens.reasoningTokens());
    }
    private void addUsage(String key,Integer value) {
        if (value != null && value>0) usage.merge(key,value,Math::addExact);
    }
    public static int outputReserveTokens(ChatOptions options,int fallback) {
        return PromptBudget.outputReserveTokens(options, null, fallback);
    }
    public BudgetedCompactionModel(ChatModel delegate,long allowedCalls,int windowTokens,int fallbackOutputTokens) {
        this.delegate=delegate; this.allowedCalls=allowedCalls;
        this.windowTokens=windowTokens; this.fallbackOutputTokens=fallbackOutputTokens;
    }
    public synchronized int calls() { return calls; }
    private synchronized void claim() {
        if (calls >= allowedCalls) { callBudgetBlocked=true; throw new CallBudgetExceeded(); }
        calls++;
    }
    private void checkPrompt(Prompt prompt) {
        if (windowTokens <= 0) return;
        long total = PromptBudget.estimateTokens(prompt, ChatModelDefaults.optionsFor(delegate), fallbackOutputTokens);
        if (total > windowTokens) {
            blockedPrompt = "摘要 Prompt 估算 " + total + " tokens 超过上下文窗口 " + windowTokens
                    + " tokens；未调用摘要模型。";
            throw new SummaryPromptOverflow();
        }
    }
    @Override public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
        checkPrompt(prompt); claim();
        try { var response=delegate.call(prompt);recordUsage(response);return response; }
        catch (CancellationException stopped) { cancellation=stopped; throw stopped; }
    }
    @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
        return reactor.core.publisher.Flux.defer(() -> {
            checkPrompt(prompt); claim();
            var last=new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.model.ChatResponse>();
            return delegate.stream(prompt).doOnNext(last::set)
                    .doOnError(CancellationException.class, stopped -> cancellation=stopped)
                    .doFinally(signal -> recordUsage(last.get()));
        });
    }
    @Override public ChatOptions getOptions() { return ChatModelDefaults.optionsFor(delegate); }
}

