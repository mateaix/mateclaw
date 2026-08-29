package vip.mate.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class A2aJsonRpcControllerTest {

    private MockMvc mvc;
    private A2aExecutionBridge bridge;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        bridge = mock(A2aExecutionBridge.class);
        A2aTaskStore store = new A2aTaskStore(100, Duration.ofMinutes(5));
        A2aProperties properties = new A2aProperties();
        properties.setEnabled(true);
        properties.setCallTimeoutMs(1000);
        A2aJsonRpcController rpc = new A2aJsonRpcController(objectMapper, properties, store, bridge);
        A2aAgentCardService cardService = mock(A2aAgentCardService.class);
        when(cardService.publicCard(any())).thenReturn(Map.of(
                "name", "MateClaw",
                "supportsAuthenticatedExtendedCard", true));
        when(cardService.authenticatedCard(any(), any())).thenReturn(Map.of(
                "name", "MateClaw",
                "skills", List.of(Map.of("id", "agent-1", "name", "Agent"))));
        A2aAgentCardController card = new A2aAgentCardController(cardService);
        mvc = MockMvcBuilders.standaloneSetup(rpc, card).build();
    }

    @Test
    void rejectsBooleanJsonRpcId() throws Exception {
        mvc.perform(post("/api/a2a")
                        .contentType("application/json")
                        .principal(auth())
                        .content("""
                                {"jsonrpc":"2.0","id":true,"method":"tasks/get","params":{"id":"task-1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    void anonymousCardOmitsSkillsAndAdvertisesExtendedCard() throws Exception {
        mvc.perform(get("/api/a2a/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsAuthenticatedExtendedCard").value(true))
                .andExpect(jsonPath("$.skills").doesNotExist());
    }

    @Test
    void authenticatedCardIncludesSkills() throws Exception {
        mvc.perform(get("/api/a2a/card").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].id").value("agent-1"));
    }

    @Test
    void sendCreatesTaskAndDuplicateRpcIdReturnsSameSnapshot() throws Exception {
        when(bridge.executeBlocking(any())).thenReturn(new A2aExecutionBridge.ExecutionResult("hello", true));
        String body = """
                {"jsonrpc":"2.0","id":"rpc-1","method":"message/send","params":{
                  "message":{"messageId":"m1","taskId":"task-1","parts":[{"kind":"text","text":"hi"}],
                  "metadata":{"skillId":"1"}},
                  "configuration":{"blocking":true}
                }}
                """;

        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth()).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value("task-1"))
                .andExpect(jsonPath("$.result.status.state").value("completed"));

        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth()).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value("task-1"))
                .andExpect(jsonPath("$.result.status.state").value("completed"));
    }

    @Test
    void duplicateTaskIdWithDifferentRpcIdIsRejected() throws Exception {
        when(bridge.executeBlocking(any())).thenReturn(new A2aExecutionBridge.ExecutionResult("hello", true));
        String first = """
                {"jsonrpc":"2.0","id":"rpc-1","method":"message/send","params":{
                  "message":{"messageId":"m1","taskId":"task-1","parts":[{"kind":"text","text":"hi"}],
                  "metadata":{"skillId":"1"}}
                }}
                """;
        String second = first.replace("\"rpc-1\"", "\"rpc-2\"");

        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth()).content(first))
                .andExpect(status().isOk());
        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth()).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32009));
    }

    @Test
    void cancelTransitionsActiveTaskToCanceled() throws Exception {
        when(bridge.executeBlocking(any())).thenReturn(new A2aExecutionBridge.ExecutionResult("hello", false));
        String send = """
                {"jsonrpc":"2.0","id":"rpc-1","method":"message/send","params":{
                  "message":{"messageId":"m1","taskId":"task-1","parts":[{"kind":"text","text":"hi"}],
                  "metadata":{"skillId":"1"}},
                  "configuration":{"blocking":true}
                }}
                """;
        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth()).content(send))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status.state", not("completed")));

        mvc.perform(post("/api/a2a").contentType("application/json").principal(auth())
                        .content("""
                                {"jsonrpc":"2.0","id":"rpc-2","method":"tasks/cancel","params":{"id":"task-1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status.state").value("canceled"));
    }

    private static UsernamePasswordAuthenticationToken auth() {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("alice", null, List.of());
        token.setDetails(1L);
        return token;
    }
}
