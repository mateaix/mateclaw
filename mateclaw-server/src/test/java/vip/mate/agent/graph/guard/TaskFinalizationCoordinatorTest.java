package vip.mate.agent.graph.guard;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskFinalizationCoordinatorTest {
    final NodeStreamingChatHelper helper=mock(NodeStreamingChatHelper.class);
    final ChatModel model=mock(ChatModel.class);
    final TaskFinalizationCoordinator coordinator=new TaskFinalizationCoordinator(helper,null);
    final ActionExecutionLedger ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("f","calculate","missing language",false)));
    static String json(String decision,String claims){return "{\"decision\":\""+decision+"\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":1,\"recoveryClaims\":"+claims+",\"reason\":\"checked\"}";}
    NodeStreamingChatHelper.StreamResult result(String text){return new NodeStreamingChatHelper.StreamResult(text,"checker thinking",null,List.of(),false,7,3,false,null,NodeStreamingChatHelper.ErrorType.NONE,false,2,1,1);}
    TaskFinalizationCoordinator.Review review(int window,int attempts,int used){return coordinator.review(model,"c","Calculate all stages","candidate",ledger,"untrusted progress",window,attempts,10,used,true,new RunState());}
    @Test void reasoningTokenExhaustionRetriesOnceWithConfiguredBudget() {
        var defaults = OpenAiChatOptions.builder().model("sensenova-6.8-flash-lite").maxTokens(8192).build();
        when(model.getDefaultOptions()).thenReturn(defaults);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            Prompt prompt = call.getArgument(1);
            assertEquals("off", ThinkingLevelHolder.get());
            assertTrue(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
            if (calls.getAndIncrement() == 0) {
                assertEquals(1024, prompt.getOptions().getMaxTokens());
                return new NodeStreamingChatHelper.StreamResult("", "Reviewing all fifteen forecast days", null,
                        List.of(), false, 10, 1024, true, "thinking_token_limit",
                        NodeStreamingChatHelper.ErrorType.NONE, false, 2, 0, 1024);
            }
            assertEquals(8192, prompt.getOptions().getMaxTokens());
            return result(json("ACCEPT", "[]"));
        });
        var check = review(16000, 0, 0);
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT, check.result().outcome());
        assertEquals(2, check.calls());
        assertEquals(17, check.usage().promptTokens());
        assertEquals(1027, check.usage().completionTokens());
        assertEquals(4, check.usage().cacheReadTokens());
        assertEquals(1025, check.usage().reasoningTokens());
        assertEquals(8192, defaults.getMaxTokens());
    }

    @Test void tokenRetryStaysWithinWindowAndKeepsObjectiveAndEvidence() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(32000).build());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            Prompt prompt = call.getArgument(1);
            assertTrue(prompt.getContents().contains("Calculate all stages"));
            assertTrue(prompt.getContents().contains("missing language"));
            assertTrue(vip.mate.agent.context.TokenEstimator.estimateTokens(prompt.getInstructions())
                    + prompt.getOptions().getMaxTokens() <= 8000);
            if (calls.getAndIncrement() == 0) {
                return new NodeStreamingChatHelper.StreamResult("", "Review in progress", null,
                        List.of(), false, 10, 1024, true, "thinking_token_limit",
                        NodeStreamingChatHelper.ErrorType.NONE, false, 0, 0, 1024);
            }
            assertTrue(prompt.getOptions().getMaxTokens() > 1024);
            return result(json("ACCEPT", "[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT, review(8000, 0, 0).result().outcome());
        assertEquals(2, calls.get());
    }

    @Test void exhaustedCallBudgetReportsTokenLimitWithoutRetrying() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(8192).build());
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("", "Review in progress", null,
                        List.of(), false, 10, 1024, true, "thinking_token_limit",
                        NodeStreamingChatHelper.ErrorType.NONE, false, 0, 0, 1024));
        var check = review(16000, 0, 49);
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, check.result().outcome());
        assertEquals("CHECKER_OUTPUT_LIMIT", check.result().reason());
        assertEquals(1, check.calls());
        assertTrue(TaskFinalizationCoordinator.incompleteAnswer(check.result().reason()).contains("token"));
        verify(helper, times(1)).streamCallSilent(any(), any(), anyString(), anyString());
    }

    @Test void repeatedExhaustionIsBoundedAndReportsAggregateUsage() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(32000).build());
        var tracker = mock(vip.mate.channel.web.ChatStreamTracker.class);
        var observed = new TaskFinalizationCoordinator(helper, tracker);
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("", "Still reviewing", null,
                        List.of(), false, 10, 1024, true, "thinking_token_limit",
                        NodeStreamingChatHelper.ErrorType.NONE, false, 2, 1, 1024));
        var check = observed.review(model, "c", "objective", "candidate", ledger, "", 16000, 0, 10, 0, true, new RunState());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, check.result().outcome());
        assertEquals("CHECKER_OUTPUT_LIMIT", check.result().reason());
        assertEquals(2, check.calls());
        assertEquals(20, check.usage().promptTokens());
        assertEquals(2048, check.usage().completionTokens());
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(helper, times(2)).streamCallSilent(any(), prompts.capture(), anyString(), anyString());
        assertEquals(8192, prompts.getAllValues().get(1).getOptions().getMaxTokens());
        var events = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tracker, times(2)).broadcastObject(eq("c"), eq("finalization_check"), events.capture());
        var completed = events.getAllValues().get(1);
        assertEquals(2, completed.get("logicalCalls"));
        assertEquals(20, completed.get("promptTokens"));
        assertEquals(2048, completed.get("completionTokens"));
        assertEquals("CHECKER_OUTPUT_LIMIT", completed.get("diagnostic"));
    }

    @Test void retryCannotTurnInvalidVerdictIntoAcceptance() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(8192).build());
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("", "Review", null, List.of(), false,
                        10, 1024, true, "thinking_token_limit", NodeStreamingChatHelper.ErrorType.NONE,
                        false, 0, 0, 1024), result("not a verdict"));
        var check = review(16000, 0, 0);
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, check.result().outcome());
        assertEquals("INVALID_VERDICT", check.result().reason());
        assertEquals(2, check.calls());
    }

    @Test void noBudgetGrowthAndSafetyCapsAreNotRetried() {
        for (String reason : List.of("thinking_token_limit", "thinking_only_no_content", "content_repetition")) {
            reset(helper);
            when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(1024).build());
            when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                    new NodeStreamingChatHelper.StreamResult("", "Review", null, List.of(), false,
                            10, 1024, true, reason, NodeStreamingChatHelper.ErrorType.NONE, false, 0, 0, 1024));
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE, review(16000, 0, 0).result().outcome());
            verify(helper, times(1)).streamCallSilent(any(), any(), anyString(), anyString());
        }
    }

    @Test void completionTokenRetryPreservesOpenAiParameterFamily() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("gpt-5")
                .maxCompletionTokens(8192).build());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            var options = (OpenAiChatOptions) ((Prompt) call.getArgument(1)).getOptions();
            assertNull(options.getMaxTokens());
            assertEquals(calls.get() == 0 ? 1024 : 8192, options.getMaxCompletionTokens());
            if (calls.getAndIncrement() == 0) return new NodeStreamingChatHelper.StreamResult("", "Review", null,
                    List.of(), false, 10, 1024, true, "thinking_token_limit", NodeStreamingChatHelper.ErrorType.NONE,
                    false, 0, 0, 1024);
            return result(json("ACCEPT", "[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT, review(16000, 0, 0).result().outcome());
        assertEquals(2, calls.get());
    }

    @Test void incompleteExplanationDistinguishesFixedCausesWithoutLeakingReviewerText() {
        var context=TaskFinalizationCoordinator.incompleteAnswer("Required execution facts or objective/candidate cannot fit context");
        var correction=TaskFinalizationCoordinator.incompleteAnswer("Correction allowance exhausted");
        var invalid=TaskFinalizationCoordinator.incompleteAnswer("Invalid verification facts");
        assertTrue(context.contains("上下文"));
        assertTrue(correction.contains("修正"));
        assertTrue(invalid.contains("核验"));
        assertNotEquals(context,correction);assertNotEquals(correction,invalid);
        String missing=TaskFinalizationCoordinator.incompleteAnswer("Required execution evidence unavailable");
        assertTrue(missing.contains("证据缺失"));assertNotEquals(context,missing);
        assertFalse(TaskFinalizationCoordinator.incompleteAnswer("SECRET_REVIEWER_TEXT").contains("SECRET_"));
    }
    @Test void completeLargeEvidenceSurvivesCompactionAndUsesAvailableWindow() {
        // Well above the former 3000-token evidence allowance; tail must remain grounded.
        String output="独立结果证据".repeat(1800)+" totalSquareSum=2094116873200";
        var receipts=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true)));
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            Prompt prompt=call.getArgument(1);
            assertTrue(prompt.getContents().contains(output));
            assertTrue(vip.mate.agent.context.TokenEstimator.estimateTokens(prompt.getInstructions())+1024<=32000);
            return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
        });
        // Empty model history: canonical ledger must preserve its newest full result.
        var review=coordinator.review(model,"c","aggregate","table",receipts,"",32000,0,10,0,true,new RunState());
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,review.result().outcome());
        assertEquals(1,review.calls());
    }
    @Test void existenceReviewDoesNotInventWholeLineOrExecutionCountRequirements() {
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            String prompt = ((Prompt)call.getArgument(1)).getContents();
            assertTrue(prompt.contains("substring anywhere"));
            assertTrue(prompt.contains("standalone line"));
            assertTrue(prompt.contains("not a claim that those tests were executed"));
            assertTrue(prompt.contains("successfulProgressUpdates"));
            return result(json("ACCEPT", "[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT, review(16000, 0, 0).result().outcome());
    }

    @Test void revisionFeedbackRequestsSpecificOffendingClauseAndGroundedReplacement() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            assertTrue(prompt.contains("quote the short offending clause"));
            assertTrue(prompt.contains("give a concrete grounded replacement"));
            assertTrue(prompt.contains("at most 256 characters"));
            return result(json("REVISE","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.REVISE,review(16000,0,0).result().outcome());
    }
    @Test void olderSkillDirectoryKeepsTotalsAfterLaterFilteredLookup() {
        String directory="Skill directory\n"+"| documented skill | description |\n".repeat(40)+"Showing: 50 of 52 skill(s).";
        var old=new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
            Map.of("toolCallId","directory","toolName","listAvailableSkills","result",directory,"success",true),1);
        var latest=new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
            Map.of("toolCallId","filtered","toolName","listAvailableSkills","result","No skills are currently available.","success",true),2);
        var ledger=ActionExecutionLedger.fromEvents(List.of(old,latest));
        var response=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("directory","listAvailableSkills",directory);
        for (String source : List.of(MateClawStateKeys.MESSAGES,MateClawStateKeys.TOOL_RESULTS,MateClawStateKeys.PENDING_EVENTS)) {
            reset(helper);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                String prompt=((Prompt)call.getArgument(1)).getContents();
                assertTrue(prompt.contains("Showing: 50 of 52 skill(s)."),"Earlier complete directory must reach the verifier");
                assertTrue(prompt.contains("No skills are currently available."),"Keep the later filtered result too");
                return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
            });
            Object retained = source.equals(MateClawStateKeys.MESSAGES)
                ? List.of(org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(response)).build())
                : source.equals(MateClawStateKeys.TOOL_RESULTS) ? List.of(response) : List.of(old);
            var check=coordinator.review(model,"c","你有哪些技能","目录显示52个技能",ledger,"",16000,0,10,0,true,
                new RunState(Map.of(source,retained)));
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT,check.result().outcome(),source);
        }
    }

    @Test void olderEvidenceRejectsWrongIdentityLengthAndConflictingCopies() {
        String output="x".repeat(600)+" total=52";
        var old=new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
            Map.of("toolCallId","old","toolName","listAvailableSkills","result",output,"success",true),1);
        var latest=new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
            Map.of("toolCallId","new","toolName","listAvailableSkills","result","No matching skills.","success",true),2);
        var ledger=ActionExecutionLedger.fromEvents(List.of(old,latest));
        var valid=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old","listAvailableSkills",output);
        for (var responses : List.of(
                List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(null,"listAvailableSkills",output)),
                List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("wrong","listAvailableSkills",output)),
                List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old","wrong",output)),
                List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old","listAvailableSkills",output+"extra")),
                List.of(valid,new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("old","listAvailableSkills",output.replace("52","99"))))) {
            reset(helper);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                String prompt=((Prompt)call.getArgument(1)).getContents();
                assertFalse(prompt.contains("total=52"));assertFalse(prompt.contains("total=99"));
                assertTrue(prompt.contains("\"resultComplete\":false"));
                return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
            });
            coordinator.review(model,"c","List observed skills","Observed subset",ledger,"",16000,0,10,0,true,
                new RunState(Map.of(MateClawStateKeys.TOOL_RESULTS,responses)));
            verify(helper).streamCallSilent(any(),any(),anyString(),anyString());
        }
    }

    @Test void latestAggregateAfterPreviewIsGroundedFromExactRetainedResponse() {
        String output="x".repeat(600)+" totalSquareSum=2094116873200";
        var receipts=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true)));
        var response=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("aggregate","execute_code",output);
        for(String source:List.of(MateClawStateKeys.TOOL_RESULTS,MateClawStateKeys.MESSAGES,MateClawStateKeys.PENDING_EVENTS)) {
            reset(helper);
            Object retained=source.equals(MateClawStateKeys.TOOL_RESULTS)?List.of(response):source.equals(MateClawStateKeys.MESSAGES)
                ?List.of(org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(response)).build())
                :List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true));
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                String prompt=((Prompt)call.getArgument(1)).getContents();
                assertTrue(prompt.contains("totalSquareSum=2094116873200"));
                assertTrue(prompt.contains("\"resultComplete\":true"));
                assertTrue(prompt.contains("\"omittedResultChars\":0"));
                return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
            });
            var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,new RunState(Map.of(source,retained)));
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT,check.result().outcome(),source);
            verify(helper).streamCallSilent(any(),any(),anyString(),anyString());
        }
    }
    @Test void canonicalCompletionWinsOverDisplayAugmentedResponse() {
        String output="x".repeat(600)+" total=42";
        var event=GraphEventPublisher.toolComplete("aggregate","execute_code",output,true);
        var receipts=ActionExecutionLedger.fromEvents(List.of(event));
        var augmented=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("aggregate","execute_code",output+" display directive");
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            assertTrue(prompt.contains("total=42"));assertFalse(prompt.contains("display directive"));
            return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
        });
        var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,
            new RunState(Map.of(MateClawStateKeys.TOOL_RESULTS,List.of(augmented),MateClawStateKeys.PENDING_EVENTS,List.of(event))));
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,check.result().outcome());
    }
    @Test void legacyMissingWrongIdentityAndActualWindowOverflowFailBeforeChecker() throws Exception {
        for(String output:List.of("x".repeat(600)+" actual-total", "x".repeat(120000)+" actual-total")) {
            var receipts=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true)));
            if (output.length() < 1000) {
                // Legacy run without retained full evidence still fails closed.
                var constructor=ActionExecutionLedger.class.getDeclaredConstructor(Map.class);
                constructor.setAccessible(true);
                receipts=constructor.newInstance(receipts.receipts());
            }
            for(String id:List.of("aggregate","wrong-id")) {
                reset(helper);
                var response=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(id,"execute_code",output);
                var state=new RunState(Map.of(MateClawStateKeys.TOOL_RESULTS,List.of(response)));
                if(id.equals("aggregate") && output.length()<1000) state=new RunState();
                var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,state);
                assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
                assertEquals(0,check.calls());verifyNoInteractions(helper);
                assertEquals(output.length()<1000 ? "Required execution evidence unavailable"
                    : "Required execution facts or objective/candidate cannot fit context", check.result().reason());
            }
        }
    }
    @Test void malformedAndForgedVerdictsFailClosedWithUsage() {
        for(String verdict:List.of("bad",json("ACCEPT","[{\"failedCallId\":\"f\",\"successfulCallId\":\"forged\"}]"))) {
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result(verdict));
            var review=review(16000,0,0);assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,review.result().outcome());
            assertEquals(1,review.calls());assertEquals(7,review.usage().promptTokens());assertEquals(2,review.usage().cacheReadTokens());
        }
    }
    @Test void requiredObjectiveAndCandidateAreNeverTruncatedToSpendCall() {
        var check=coordinator.review(model,"c","x".repeat(20000),"candidate",ledger,"",2048,0,10,0,true,new RunState());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());assertEquals(0,check.calls());verifyNoInteractions(helper);
    }
    @Test void callCeilingAndMissingCoreRejectBeforeTransport() {
        assertEquals(0,review(16000,0,50).calls());assertEquals(0,review(64,0,0).calls());verifyNoInteractions(helper);
    }
    @Test void checkerHasNoCallbacksAndRestoresThinkingScopeOnErrorAndCancel() {
        var callback=mock(ToolCallback.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("test").toolCallbacks(List.of(callback)).maxTokens(10000).build());
        for(RuntimeException error:List.of(new IllegalStateException("offline"),new CancellationException("stop"))) {
            reset(helper);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                Prompt prompt=call.getArgument(1);assertEquals("off",ThinkingLevelHolder.get());
                assertTrue(((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks().isEmpty());
                assertEquals(1024,prompt.getOptions().getMaxTokens());throw error;
            });
            ThinkingLevelHolder.set("high");
            try {
                var review=review(16000,0,0);assertEquals("high",ThinkingLevelHolder.get());
                assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,review.result().outcome());assertEquals(1,review.calls());
                assertEquals(error instanceof CancellationException,review.stopped());verifyNoInteractions(callback);
            } finally {ThinkingLevelHolder.clear();}
        }
    }
    @Test void checkerInspectsRetryProseEvenWhenRecoveryClaimsArrayIsEmpty() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String instruction=((Prompt)call.getArgument(1)).getInstructions().getFirst().getText();
            for(String required:List.of("随后补齐 language=\"python\" 重试","补齐参数后成功","retried successfully",
                    "empty recoveryClaims does not excuse unsupported candidate prose", "original-call retry remains unverified",
                    "recoveredEarlierFailure=true", "not evidence that the original failed call was retried or recovered"))
                assertTrue(instruction.contains(required),required);
            return result(json("REVISE","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.REVISE,review(16000,0,0).result().outcome());
    }
    @Test void reviewerProtocolGivesConciseReasonLimitAndStrictlyValidExample() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String instruction=((Prompt)call.getArgument(1)).getInstructions().getFirst().getText();
            assertTrue(instruction.contains("reason must be at most 256 characters"));
            assertTrue(instruction.contains("Decision choices: ACCEPT, REVISE, CONTINUE"));
            assertTrue(instruction.contains("No markdown fences, preamble, trailing text, or extra explanations"));
            assertFalse(instruction.contains("ACCEPT|REVISE|CONTINUE"));
            int start=instruction.indexOf("{\"decision\":");
            String example=instruction.substring(start,instruction.indexOf('}',start)+1);
            var parsed=new TaskFinalizationGate().parse(example).orElseThrow();
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT,parsed.decision());
            assertTrue(parsed.reason().length()<=256);
            return result(json("ACCEPT","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,review(16000,0,0).result().outcome());
        assertEquals(1024,TaskFinalizationGate.MAX_REASON_CHARS);
    }
    @Test void checkerPromptSeparatesObjectiveCandidateCanonicalEvidenceAndRuntime() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            for(String required:List.of("Calculate all stages","candidate","CANONICAL FACTS","RUNTIME: ACTIVE","failedCallCount","untrusted progress","print(1)","budget exhausted"))assertTrue(prompt.contains(required),required);
            return result(json("ACCEPT","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,review(16000,2,0).result().outcome());
    }
    @Test void stoppedOrPartialCheckerNeverAcceptsAndPreservesUsage() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(json("ACCEPT","[]"),"",null,List.of(),false,7,3,true,null,NodeStreamingChatHelper.ErrorType.NONE,true,2,1,1));
        var check=review(16000,0,0);assertTrue(check.stopped());assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());assertEquals(7,check.usage().promptTokens());
    }
    @Test void safeDiagnosticsDistinguishSchemaAndCanonicalFactRejectionWithoutRawText() {
        var tracker=mock(vip.mate.channel.web.ChatStreamTracker.class);
        var observed=new TaskFinalizationCoordinator(helper,tracker);
        var cases=Map.of("SECRET_INVALID_VERDICT","INVALID_VERDICT",
                json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":9"),"FAILED_CALL_COUNT_MISMATCH",
                json("ACCEPT","[{\"failedCallId\":\"f\",\"successfulCallId\":\"forged\"}]"),"UNSUPPORTED_RECOVERY");
        for(var entry:cases.entrySet()) {
            reset(helper,tracker);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result(entry.getKey()));
            var check=observed.review(model,"c","SECRET_CURRENT_OBJECTIVE","SECRET_CANDIDATE",ledger,"SECRET_PROGRESS",16000,0,10,0,true,new RunState());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
            var data=org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(tracker,times(2)).broadcastObject(eq("c"),eq("finalization_check"),data.capture());
            Map<?,?> started=data.getAllValues().get(0),completed=data.getAllValues().get(1);
            for(String field:List.of("objectiveTokens","candidateTokens","progressHintsTokens"))
                assertTrue(started.get(field) instanceof Integer,field);
            assertEquals(entry.getValue(),completed.get("diagnostic"));
            assertEquals(entry.getValue().equals("INVALID_VERDICT")?"INVALID_JSON":"NONE",completed.get("parseFailureCode"));
            assertTrue(completed.get("responseTextChars") instanceof Integer);
            assertTrue(completed.get("responseThinkingChars") instanceof Integer);
            assertFalse(data.getAllValues().toString().contains("SECRET_"));
            assertFalse(data.getAllValues().toString().contains("forged"));
        }
    }
    @Test void fencedVerdictRemainsRejectedAndReportsOnlyFixedParseCategory() {
        var tracker=mock(vip.mate.channel.web.ChatStreamTracker.class);
        var observed=new TaskFinalizationCoordinator(helper,tracker);
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result("```json\n"+json("ACCEPT","[]")+"\n```"));
        var check=observed.review(model,"c","objective","candidate",ledger,"",16000,0,10,0,true,new RunState());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
        var data=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tracker,times(2)).broadcastObject(eq("c"),eq("finalization_check"),data.capture());
        assertEquals("FENCED_JSON",data.getAllValues().get(1).get("parseFailureCode"));
        assertFalse(data.getAllValues().toString().contains("```"));
    }

    @Test void providerErrorIsReportedAsCheckerErrorWithShareableCode() {
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("", "", null, List.of(), false, 0, 0, false,
                        "LLM 调用失败: upstream reset", NodeStreamingChatHelper.ErrorType.SERVER_ERROR));
        var check = review(16000, 0, 0);
        assertEquals("CHECKER_ERROR", check.result().reason());
        String answer = TaskFinalizationCoordinator.incompleteAnswer(check.result().reason());
        assertTrue(answer.contains("模型服务报错"));
        assertTrue(answer.endsWith("（诊断码：CHECKER_ERROR）"));
        assertFalse(answer.contains("upstream reset"));
    }
    @Test void locallyTruncatedVerdictIsPartialNotProviderError() {
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("{\"decision\":", "", null, List.of(), false, 7, 3, true,
                        "content_repetition", NodeStreamingChatHelper.ErrorType.NONE));
        assertEquals("CHECKER_PARTIAL", review(16000, 0, 0).result().reason());
    }
    @Test void nullVerifierResponseAndExceptionHaveDistinctCodes() {
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(null);
        assertEquals("CHECKER_UNAVAILABLE", review(16000, 0, 0).result().reason());
        reset(helper);
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
        var thrown = review(16000, 0, 0).result().reason();
        assertEquals("CHECKER_EXCEPTION", thrown);
        assertNotEquals(TaskFinalizationCoordinator.incompleteAnswer("CHECKER_UNAVAILABLE"),
                TaskFinalizationCoordinator.incompleteAnswer(thrown));
    }
    @Test void summaryFailureCodesAreDistinctAndFreeTextGetsNoCode() {
        assertTrue(TaskFinalizationCoordinator.incompleteAnswer("Summary unavailable").contains("诊断码：SUMMARY_UNAVAILABLE"));
        assertTrue(TaskFinalizationCoordinator.incompleteAnswer("Summary verification unavailable").contains("诊断码：SUMMARY_EXCEPTION"));
        assertFalse(TaskFinalizationCoordinator.incompleteAnswer("SECRET_REVIEWER_TEXT").contains("诊断码"));
    }
    @Test void onlyVerifierInfrastructureFailuresAllowUnverifiedPublication() {
        for (String reason : List.of("CHECKER_ERROR","CHECKER_UNAVAILABLE","CHECKER_EXCEPTION","CHECKER_PARTIAL","CHECKER_OUTPUT_LIMIT","CHECKER_TOOL_CALL"))
            assertTrue(TaskFinalizationCoordinator.checkerUnavailable(reason), reason);
        for (String reason : List.of("INVALID_VERDICT","FAILED_CALL_COUNT_MISMATCH","UNSUPPORTED_RECOVERY","CONTRADICTORY_OUTCOME",
                "TASK_UNFINISHED","CORRECTION_LIMIT","Required execution evidence unavailable","SUMMARY_OUTPUT_LIMIT"))
            assertFalse(TaskFinalizationCoordinator.checkerUnavailable(reason), reason);
        assertFalse(TaskFinalizationCoordinator.checkerUnavailable(null));
    }
    @Test void oversizedVerifierRequestIsContextOverflowNotPublishable() {
        when(helper.streamCallSilent(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("", "", null, List.of(), false, 0, 0, false,
                        "请求体过大（HTTP 413）", NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE));
        String reason = review(16000, 0, 0).result().reason();
        assertEquals("CHECKER_CONTEXT_EXCEEDED", reason);
        assertFalse(TaskFinalizationCoordinator.checkerUnavailable(reason));
        assertTrue(TaskFinalizationCoordinator.incompleteAnswer(reason).contains("超出可用上下文"));
    }
}
