package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.llm.model.*;
import vip.mate.llm.service.ModelProviderService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoordinatorModelScopeTest {
    @Test void isolatesModelAndNestedProviderSearchIncludingKimiWithoutMutatingSharedConfiguration() {
        var service = mock(ModelProviderService.class);
        var builder = mock(ChatModelBuilder.class);
        when(builder.supportedProtocol()).thenReturn(ModelProtocol.OPENAI_COMPATIBLE);
        var provider = new ModelProviderEntity(); provider.setProviderId("kimi"); provider.setChatModel("openai");
        provider.setGenerateKwargs("{\"chatOptions\":{\"enableSearch\":true},\"tools\":[{\"type\":\"web_search\"}]}");
        when(service.getProviderConfig("kimi")).thenReturn(provider);
        when(service.readProviderGenerateKwargs(provider)).thenReturn(Map.of("chatOptions",Map.of("enableSearch",true),"tools",List.of(Map.of("type","web_search"))));
        var model = new ModelConfigEntity(); model.setProvider("kimi"); model.setEnableSearch(true);
        var factory = new ProviderChatModelFactory(List.of(builder),service);
        assertThrows(vip.mate.exception.MateClawException.class, () -> factory.buildFor(model,null,true));
        verify(builder, never()).build(any(),any(),any());
        provider.setGenerateKwargs("{\"chatOptions\":{\"enableSearch\":true}}");
        when(service.readProviderGenerateKwargs(provider)).thenReturn(Map.of("chatOptions",Map.of("enableSearch",true)));
        when(builder.build(any(),any(),any())).thenAnswer(inv -> {
            ModelConfigEntity scopedModel=inv.getArgument(0); ModelProviderEntity scopedProvider=inv.getArgument(1);
            assertNotSame(model,scopedModel); assertNotSame(provider,scopedProvider);
            assertFalse(scopedModel.getEnableSearch());
            assertEquals("{\"enableSearch\":false}",scopedProvider.getGenerateKwargs());
            return mock(ChatModel.class);
        });
        assertNotNull(factory.buildFor(model,null,true));
        model.setModelName("gpt-4o-search-preview");
        assertThrows(vip.mate.exception.MateClawException.class, () -> factory.buildFor(model,null,true));
        model.setModelName(null);
        assertTrue(model.getEnableSearch()); assertTrue(provider.getGenerateKwargs().contains("true"));
        reset(builder); factory.buildFor(model,null); verify(builder).build(same(model),same(provider),isNull());
    }
}
