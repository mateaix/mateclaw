package vip.mate.memory.fact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.projection.FactProjectionBuilder;
import vip.mate.memory.fact.provider.FactMemoryProvider;
import vip.mate.memory.fact.query.FactQueryService;
import vip.mate.memory.fact.tool.FactQueryTool;

import static org.mockito.Mockito.*;

class FactMemoryProviderOwnerSafetyTest {

    @Test
    @DisplayName("ownerless memory-write callbacks rebuild canonical rows instead of projecting as TEAM")
    void ownerlessWriteCallbackUsesOwnerAwareFullRebuild() {
        FactProjectionBuilder builder = mock(FactProjectionBuilder.class);
        MemoryProperties properties = new MemoryProperties();
        properties.getFact().setProjectionEnabled(true);
        FactMemoryProvider provider = new FactMemoryProvider(
                mock(FactQueryService.class), builder, mock(FactQueryTool.class), properties);

        provider.onMemoryWrite(7L, "structured/user.md", "remember", "content");

        verify(builder).rebuildAll(7L);
        verify(builder, never()).rebuildOne(anyLong(), anyString(), anyString());
    }
}
