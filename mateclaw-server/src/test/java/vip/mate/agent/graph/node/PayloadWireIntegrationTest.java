package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.PrefixBudgetPlan;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.PayloadReduction;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.chatmodel.OpenAiCompatibleChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** Actual HTTP bodies/statuses through the production provider and reasoning recovery. */
class PayloadWireIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings={"recover", "mandatory", "stillTooLarge", "reviewTooLarge", "unfinishedRevision"})
    void boundsWireRetriesAndPreservesMandatoryUtf8Input(String mode) throws Exception {
        var bodies = Collections.synchronizedList(new ArrayList<byte[]>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            bodies.add(request);
            boolean reject = mode.equals("reviewTooLarge") ? bodies.size()>1
                    : !mode.equals("unfinishedRevision") && request.length > (mode.equals("stillTooLarge") ? 64 : 40_000);
            String response = reject
                    ? "{\"error\":{\"message\":\"body limit exceeded\",\"type\":\"invalid_request_error\"}}"
                    : "data: {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"verified result\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            if(mode.equals("unfinishedRevision") && bodies.size()>1) {
                String verdict="{\"decision\":\"REVISE\",\"taskComplete\":false,\"remainingWork\":true,\"failedCallCount\":1,\"recoveryClaims\":[],\"reason\":\"Restore the requested pending checkpoint before answering\"}";
                response=response.replace("\"verified result\"",new ObjectMapper().writeValueAsString(verdict));
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",reject?"application/json":"text/event-stream");
            exchange.sendResponseHeaders(reject?413:200,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var service=mock(ModelProviderService.class);
            when(service.isProviderConfigured(anyString())).thenReturn(true);
            when(service.readProviderGenerateKwargs(any())).thenReturn(Map.of());
            var config=new ModelConfigEntity(); config.setModelName("fixture"); config.setRequestTimeoutSeconds(3);
            var provider=new ModelProviderEntity(); provider.setProviderId("fixture"); provider.setRequireApiKey(false);
            provider.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var model=new OpenAiCompatibleChatModelBuilder(service,null,null,null).build(config,provider,null);
            String schema="{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"},\"args\":{\"type\":\"string\"}},\"required\":[\"language\",\"code\"],\"additionalProperties\":false}";
            var callback=new org.springframework.ai.tool.ToolCallback() {
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return org.springframework.ai.tool.definition.ToolDefinition.builder().name("execute_code")
                            .description("Synthetic fixture only").inputSchema(schema).build();
                }
                public String call(String arguments) { throw new AssertionError("History recovery must not replay tools"); }
            };
            var node=new ReasoningNode(model,AgentToolSet.fromCallbacks(List.of(),List.of(callback)),null,false,
                    new NodeStreamingChatHelper(mock(ChatStreamTracker.class)),null,null,1024,null,null,null,null);
            node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,1_000_000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
            String objective="必要输入：保持原文与引号 \"参数\" 🧪";
            if (mode.equals("mandatory")) objective = objective.repeat(2000);
            String originalArguments=new ObjectMapper().writeValueAsString(Map.of(
                    "language","python","code","print(\"历史🧪\")","args","[\"空 格\",\"back\\\\slash\"]"));
            List<Message> canonical=new ArrayList<>(); canonical.add(new UserMessage(objective));
            if (!mode.equals("mandatory")) for(int i=0;i<20;i++) {
                String id="wire-"+i;
                canonical.add(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall(id,"function","execute_code",originalArguments))).build());
                canonical.add(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(id,"execute_code","历史🧪".repeat(500)))).build());
            }
            int originalSize=canonical.size();
            String originalHistory=canonical.toString();
            var values=new HashMap<String,Object>();
            boolean guardedReview=mode.equals("reviewTooLarge") || mode.equals("unfinishedRevision");
            if(guardedReview) {
                values.put(CURRENT_ITERATION,2);
                var events=List.of(vip.mate.agent.GraphEventPublisher.toolComplete("f","calculate","missing language",false),
                        vip.mate.agent.GraphEventPublisher.toolComplete("s","calculate","42",true));
                values.put(PENDING_EVENTS,events);
                values.put(ACTION_EXECUTION_LEDGER,vip.mate.agent.graph.state.ActionExecutionLedger.fromEvents(events));
                node.setFinalizationCoordinator(new vip.mate.agent.graph.guard.TaskFinalizationCoordinator(
                        new NodeStreamingChatHelper(mock(ChatStreamTracker.class)),null));
            }
            values.putAll(Map.of(CONVERSATION_ID,"wire-"+mode,SYSTEM_PROMPT,"MANDATORY_SKILL",
                    USER_MESSAGE,objective,MESSAGES,canonical,MAX_ITERATIONS,100));
            var output=node.apply(new RunState(values));
            int expected=mode.equals("mandatory")?1:2;
            assertEquals(expected,bodies.size(),"no SDK/helper/fallback or unchanged payload retries");
            assertEquals(expected,output.get(LLM_CALL_COUNT));
            assertEquals(originalSize,canonical.size(),"canonical history must survive local reduction");
            assertEquals(originalHistory,canonical.toString(),"history contents and arguments must remain intact");
            if(expected==2 && !guardedReview) assertTrue(bodies.get(1).length<bodies.get(0).length,"serialized UTF-8 request must shrink");
            var json=new ObjectMapper();
            for(byte[] body:bodies) {
                JsonNode messages=json.readTree(body).get("messages");
                boolean hasObjective=false,hasSkill=false;
                Set<String> calls=new HashSet<>(),responses=new HashSet<>();
                for(JsonNode message:messages) {
                    hasObjective |= message.path("content").asText().contains(objective);
                    hasSkill |= message.path("content").asText().contains("MANDATORY_SKILL");
                    for(JsonNode call:message.path("tool_calls")) {
                        calls.add(call.path("id").asText());
                        assertEquals(originalArguments,call.at("/function/arguments").asText());
                    }
                    if(message.path("role").asText().equals("tool")) responses.add(message.path("tool_call_id").asText());
                }
                assertTrue(hasObjective);
                if(!guardedReview || body==bodies.get(0)) assertTrue(hasSkill);
                assertEquals(calls,responses);
                if(!guardedReview || body==bodies.get(0))
                    assertEquals(json.readTree(schema),json.readTree(body).at("/tools/0/function/parameters"));
            }
            if(guardedReview) {
                if(mode.equals("unfinishedRevision")) {
                    assertEquals(true,output.get(CONTINUE_REASONING));
                    assertTrue(String.valueOf(output.get(MESSAGES)).contains("Requested outcome: CONTINUE"));
                    assertEquals(0,output.get(FINALIZATION_ATTEMPTS));
                } else assertEquals("incomplete",output.get(FINISH_REASON));
                assertFalse(String.valueOf(output.get(FINAL_ANSWER)).contains("verified result"));
                assertEquals(false,output.get(CONTENT_STREAMED));
                assertFalse(json.readTree(bodies.get(1)).has("tools"),"review must not replay tools");
            } else assertEquals(mode.equals("recover")?"verified result":"[错误] "+PayloadReduction.INCOMPLETE,output.get(FINAL_ANSWER));
        } finally { server.stop(0); }
    }
    @org.junit.jupiter.api.Test
    void historicalMedia413RecoveryShrinksRealHttpBodyAndKeepsCurrentAttachment() throws Exception {
        var bodies = Collections.synchronizedList(new ArrayList<byte[]>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes(); bodies.add(request);
            boolean reject = request.length > 40_000;
            String response = reject ? "{\"error\":{\"message\":\"body limit exceeded\"}}"
                    : "data: {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"verified current attachment\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reject ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(reject ? 413 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var service = mock(ModelProviderService.class);
            when(service.isProviderConfigured(anyString())).thenReturn(true);
            when(service.readProviderGenerateKwargs(any())).thenReturn(Map.of());
            var config = new ModelConfigEntity(); config.setModelName("fixture"); config.setRequestTimeoutSeconds(3);
            var provider = new ModelProviderEntity(); provider.setProviderId("fixture"); provider.setRequireApiKey(false);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var model = new OpenAiCompatibleChatModelBuilder(service, null, null, null).build(config, provider, null);
            var node = new ReasoningNode(model, AgentToolSet.fromCallbacks(List.of(), List.of()), null, false,
                    new NodeStreamingChatHelper(mock(ChatStreamTracker.class)), null, null, 1024, null, null, null, null);
            node.setPrefixBudgetPlan(new PrefixBudgetPlan(true, 1_000_000, PrefixBudgetPlan.Profile.NORMAL, 0,0,0,0,0,0,0));
            byte[] oldBytes = new byte[80_000];
            byte[] currentBytes = new byte[]{1,2,3,4};
            var old = UserMessage.builder().text("old image").media(new org.springframework.ai.content.Media(
                    org.springframework.util.MimeTypeUtils.IMAGE_PNG, new org.springframework.core.io.ByteArrayResource(oldBytes))).build();
            var active = new vip.mate.agent.BaseAgent.CurrentTurnUserMessage(UserMessage.builder().text("inspect current")
                    .media(new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                            new org.springframework.core.io.ByteArrayResource(currentBytes))).build(), null).userMessage();
            var canonical = List.<Message>of(old, new AssistantMessage("old answer"), active);
            var output = node.apply(new RunState(Map.of(CONVERSATION_ID, "wire-media-recovery", SYSTEM_PROMPT, "MANDATORY_SKILL",
                    USER_MESSAGE, "inspect current", MESSAGES, canonical, MAX_ITERATIONS, 100)));
            assertEquals(2, bodies.size()); assertEquals(2, output.get(LLM_CALL_COUNT));
            String original = new String(bodies.get(0), StandardCharsets.UTF_8);
            String retry = new String(bodies.get(1), StandardCharsets.UTF_8);
            assertTrue(original.contains(Base64.getEncoder().encodeToString(oldBytes)));
            assertFalse(retry.contains(Base64.getEncoder().encodeToString(oldBytes)));
            assertTrue(retry.contains(Base64.getEncoder().encodeToString(currentBytes)));
            assertTrue(retry.contains("inspect current")); assertTrue(retry.contains("MANDATORY_SKILL"));
            assertTrue(bodies.get(1).length < bodies.get(0).length);
            assertEquals("verified current attachment", output.get(FINAL_ANSWER));
            assertEquals(1, old.getMedia().size()); assertEquals(1, active.getMedia().size());
        } finally { server.stop(0); }
    }
}
