package vip.mate.agent.context;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import vip.mate.agent.runtime.state.RunState;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
class BudgetedCompactionModelTest {
 @Test void currentOptionsAccessorRetainsProviderDefaults() {
  var delegate=mock(ChatModel.class);
  var options=org.springframework.ai.openai.OpenAiChatOptions.builder().model("summary-model").maxTokens(1024).build();
  when(delegate.getOptions()).thenReturn(options);
  var model=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertSame(options,model.getOptions());
 }
 @Test void veryLargeOutputReserveCannotOverflowTotalAndBypassGuard() {
  var delegate=mock(ChatModel.class);
  var prompt=new Prompt("summary",ChatOptions.builder().maxTokens(Integer.MAX_VALUE).build());
  var guarded=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertThrows(BudgetedCompactionModel.SummaryPromptOverflow.class,()->guarded.call(prompt));
  assertEquals(0,guarded.calls());verify(delegate,never()).call(any(Prompt.class));
 }
 @Test void inheritedCompletionReserveIsCountedWhenRuntimeOverridesOnlyMaxTokens() {
  var delegate=mock(ChatModel.class);
  when(delegate.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(30000).build());
  var prompt=new Prompt("summary",org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(512).build());
  var guarded=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertThrows(BudgetedCompactionModel.SummaryPromptOverflow.class,()->guarded.call(prompt));
  assertEquals(0,guarded.calls());verify(delegate,never()).call(any(Prompt.class));
 }
 @Test void inheritedDefaultToolSchemasAreCountedBeforeStreamingProviderInvocation() {
  var delegate=mock(ChatModel.class);var tool=mock(org.springframework.ai.tool.ToolCallback.class);
  when(delegate.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.empty());
  when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
    .name("large").description("界".repeat(1000)).inputSchema("{\"type\":\"object\"}").build());
  when(delegate.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().toolCallbacks(List.of(tool)).maxTokens(64).build());
  var prompt=new Prompt("summary",org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(64).build());
  var guarded=new BudgetedCompactionModel(delegate,2,1000,64);
  assertThrows(BudgetedCompactionModel.SummaryPromptOverflow.class,()->guarded.stream(prompt).blockLast());
  assertEquals(0,guarded.calls());verify(delegate,never()).stream(any(Prompt.class));
 }
 @Test void exactInvocationAndAvailableUsageAccumulateWithoutMutatingInputState() {
  var delegate=mock(ChatModel.class);
  when(delegate.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))),ChatResponseMetadata.builder().usage(new DefaultUsage(19,11,30,null,7L,3L)).build()));
  var model=new BudgetedCompactionModel(delegate,2,24000,1024);
  model.call(new Prompt("one"));model.call(new Prompt("two"));
  var original=new RunState(Map.of(PROMPT_TOKENS,5));var updated=model.withUsage(original);
  assertEquals(2,model.calls());assertEquals(43,updated.value(PROMPT_TOKENS,0));assertEquals(22,updated.value(COMPLETION_TOKENS,0));assertEquals(14,updated.value(CACHE_READ_TOKENS,0));assertEquals(6,updated.value(CACHE_WRITE_TOKENS,0));assertEquals(5,original.value(PROMPT_TOKENS,0));
  assertThrows(BudgetedCompactionModel.CallBudgetExceeded.class,()->model.call(new Prompt("three")));
  assertTrue(model.callBudgetBlocked());assertEquals(2,model.calls());verify(delegate,times(2)).call(any(Prompt.class));
 }
 @Test void knownOverflowHasNoInvocationOrUsageAndDisabledWindowPreservesLegacy() {
  var delegate=mock(ChatModel.class);var prompt=new Prompt(List.of(new UserMessage("界".repeat(30000))),ChatOptions.builder().maxTokens(512).build());
  var guarded=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertThrows(BudgetedCompactionModel.SummaryPromptOverflow.class,()->guarded.call(prompt));
  assertEquals(0,guarded.calls());assertTrue(guarded.usage().isEmpty());verify(delegate,never()).call(any(Prompt.class));
  var disabled=new BudgetedCompactionModel(delegate,2,0,1024);disabled.call(prompt);assertEquals(1,disabled.calls());verify(delegate).call(prompt);
 }
 @Test void completionOnlyActualSdkOptionsReserveTheirDeclaredOutput() {
  var delegate=mock(ChatModel.class);
  var options=org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(30000).build();
  assertNull(options.getMaxTokens());assertEquals(30000,options.getMaxCompletionTokens());
  var model=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertThrows(BudgetedCompactionModel.SummaryPromptOverflow.class,()->model.call(new Prompt("summary",options)));
  assertEquals(0,model.calls());verify(delegate,never()).call(any(Prompt.class));
 }
 @Test void reserveUsesConservativeActualFieldsIncludingMutatedSummaryOptions() {
  var defaults=org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(30000).build();
  var summary=defaults.mutate().maxTokens(512).build();
  assertEquals(30000,BudgetedCompactionModel.outputReserveTokens(summary,1024));
  assertEquals(30000,vip.mate.agent.context.BudgetedCompactionModel.outputReserveTokens(defaults,1024));
 }
 @Test void cancellationIsRecordedEvenWhenCallerSwallowsIt() {
  var delegate=mock(ChatModel.class);var cancellation=new CancellationException("stop");
  when(delegate.call(any(Prompt.class))).thenThrow(cancellation);
  var model=new BudgetedCompactionModel(delegate,2,24000,1024);
  assertSame(cancellation,assertThrows(CancellationException.class,()->model.call(new Prompt("summary"))));
  assertSame(cancellation,model.cancellation());assertEquals(1,model.calls());assertTrue(model.usage().isEmpty());
 }
}
