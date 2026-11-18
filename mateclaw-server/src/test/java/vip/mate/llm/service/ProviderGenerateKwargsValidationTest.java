package vip.mate.llm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.*;
import vip.mate.llm.repository.ModelProviderMapper;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderGenerateKwargsValidationTest {
    static Stream<Map<String,Object>> invalidKwargs() {
        return Stream.of(Map.of("temperature",-.1),Map.of("temperature",Double.NaN),
          Map.of("top_p",1.1),Map.of("top_p","secret-do-not-echo"),Map.of("max_tokens",0),
          Map.of("max_tokens",1.5),Map.of("max_completion_tokens",2147483648L),
          Map.of("maxCompletionTokens",-1),Map.of("max_tokens","4.5"),Map.of("chat_options",Map.of("topP",2)),
          Map.of("chatOptions",false),Map.of("temperature","NaN"),Map.of("maxTokens",1.5),Map.of("max_tokens",new java.math.BigDecimal("1.000000000000000000000001")));
    }
    @ParameterizedTest @MethodSource("invalidKwargs")
    void invalidJsonParametersCannotMutateProviderOrPublishChanges(Map<String,Object> kwargs) {
        var mapper = mock(ModelProviderMapper.class); var events = mock(ApplicationEventPublisher.class);
        var service = new ModelProviderService(mapper,mock(ModelConfigService.class),events,null,null,null,null,null,null);
        var provider = new ModelProviderEntity(); provider.setProviderId("custom"); provider.setApiKey("existing-key");
        provider.setGenerateKwargs("{}"); when(mapper.selectById("custom")).thenReturn(provider);
        var request = new ProviderConfigRequest(); request.setApiKey("replacement"); request.setGenerateKwargs(kwargs);
        var error = assertThrows(MateClawException.class,() -> service.updateProviderConfig("custom",request));
        assertFalse(error.getMessage().contains("secret-do-not-echo"));
        assertEquals("existing-key",provider.getApiKey()); assertEquals("{}",provider.getGenerateKwargs());
        verifyNoInteractions(mapper); verifyNoInteractions(events);
    }
}
