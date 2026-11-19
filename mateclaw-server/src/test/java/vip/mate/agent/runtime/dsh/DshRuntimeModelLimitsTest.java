package vip.mate.agent.runtime.dsh;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import vip.mate.config.ConversationWindowProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfigService;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Captures the real subprocess boundary instead of testing an unused request builder. */
class DshRuntimeModelLimitsTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({
            "8192,128000,8192,custom-model,128000",
            "256000,128000,64000,custom-model,128000",
            "16384,8192,4096,custom-model,128000",
            ",128000,4096,custom-model,128000",
            "0,128000,4096,custom-model,128000",
            "-1,0,4096,custom-model,128000",
            "8192,128000,8192,,128000",
            "256000,,64000,custom-model,128000",
            "8192,0,512,custom-model,1024",
            "8192,-1,512,custom-model,1024",
            ",,4096,uncatalogued,128000",
            ",,512,uncatalogued,1024",
            "-1,-1,4096,custom-model,-1"
    })
    void sendsBoundedConfiguredOutputBudget(Integer output, Integer window, int expected, String requested, int globalWindow) throws Exception {
        Path capture = temp.resolve("initialize.json");
        Path script = temp.resolve("fake-dsh.sh");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s,%s,%s' "$MATECLAW_DSH_MODEL" "$MATECLAW_DSH_CONTEXT_WINDOW" "$MATECLAW_DSH_MAX_TOKENS" > model-env.txt
                IFS= read -r initialize
                printf '%s\\n' "$initialize" > initialize.json
                id=$(printf '%s' "$initialize" | sed -n 's/.*"id":"\\([^"]*\\)".*/\\1/p')
                printf '{"jsonrpc":"2.0","id":"%s","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime"}}}\\n' "$id"
                IFS= read -r prompt
                session=$(printf '%s' "$prompt" | sed -n 's/.*"sessionId":"\\([^"]*\\)".*/\\1/p')
                printf '%s\\n' '{"jsonrpc":"2.0","id":"prompt-limit-test","result":{"messageId":"m1"}}'
                printf '{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"%s","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}\\n' "$session"
                printf '{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"%s","event":{"seq":2,"type":"turn/end","data":{"reason":{"kind":"completed"}}}}}\\n' "$session"
                printf '{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"%s","status":"idle"}}\\n' "$session"
                IFS= read -r shutdown
                """);
        assertTrue(script.toFile().setExecutable(true));
        var config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(
                script.toString(), "", temp.toString(), "", "", "", "sdk", List.of(), temp.resolve("homes").toString()));
        var models = mock(ModelConfigService.class);
        var model = new ModelConfigEntity();
        model.setModelName("custom-model");
        model.setProvider("custom-provider");
        model.setMaxTokens(output);
        model.setMaxInputTokens(window);
        when(models.resolveModel(any())).thenReturn("uncatalogued".equals(requested) ? null : model);
        var providers = mock(ModelProviderService.class);
        var provider = new ModelProviderEntity();
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        when(providers.getProviderConfig("custom-provider")).thenReturn(provider);
        var properties = new ConversationWindowProperties();
        properties.setDefaultMaxInputTokens(globalWindow);
        var service = new DshRuntimeService(new ObjectMapper(), models, providers, config, properties);
        var agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        var result = service.stream(agent, "hello", "limit-test", requested)
                .collectList().block(Duration.ofSeconds(5));
        assertNotNull(result);
        var params = new ObjectMapper().readTree(Files.readString(capture)).path("params");
        assertEquals("uncatalogued".equals(requested) ? requested : "custom-model", params.path("model").asText());
        assertTrue(params.path("maxTokens").isIntegralNumber(), "SDK must receive an explicit numeric output cap");
        assertEquals(expected, params.path("maxTokens").intValue());
        int configuredWindow = "uncatalogued".equals(requested) ? 0 : window == null ? 0 : window;
        int expectedWindow = configuredWindow > 0 ? configuredWindow : globalWindow > 0 ? globalWindow : 128000;
        assertEquals(params.path("model").asText() + "," + expectedWindow + "," + expected, Files.readString(temp.resolve("model-env.txt")));
        assertFalse(params.has("contextWindow"), "SDK initialize does not support this field");
        verify(models, times(1)).resolveModel(any());
    }
    @Test
    void invalidTinyWindowFailsInsteadOfSendingAnotherImpossibleRequest() {
        var model = new ModelConfigEntity();
        model.setMaxInputTokens(1);
        assertThrows(IllegalArgumentException.class, () -> DshRuntimeService.resolveMaxOutputTokens(model, 128000));
    }

    @Test
    void tinyWindowCapNeverUsesAFloorLargerThanTheWindow() {
        var model = new ModelConfigEntity();
        model.setMaxInputTokens(600);
        model.setMaxTokens(Integer.MAX_VALUE);
        assertEquals(300, DshRuntimeService.resolveMaxOutputTokens(model, 128000));
    }

}
