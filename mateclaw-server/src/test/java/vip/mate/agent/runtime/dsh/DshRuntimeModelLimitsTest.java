package vip.mate.agent.runtime.dsh;

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
                IFS= read -r initialize
                printf '%s\\n' "$initialize" > "$1"
                printf '%s\\n' '{"jsonrpc":"2.0","id":"init-limit-test","result":{}}'
                IFS= read -r prompt
                printf '%s\\n' '{"jsonrpc":"2.0","id":"prompt-limit-test","result":{}}'
                printf '%s\\n' '{"jsonrpc":"2.0","method":"session.status","params":{"status":"idle"}}'
                """);
        var config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(
                "/bin/sh \"" + script + "\" \"" + capture + "\"", "", temp.toString(), "", "", ""));
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
