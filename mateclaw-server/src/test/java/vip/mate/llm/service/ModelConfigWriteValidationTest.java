package vip.mate.llm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelConfigWriteValidationTest {
    private ModelConfigEntity model() {
        var m = new ModelConfigEntity(); m.setId(1L); m.setName("Custom model"); m.setModelName("custom-model");
        m.setProvider("custom"); m.setEnabled(true); m.setIsDefault(false); return m;
    }
    static Stream<Arguments> invalidModels() {
        return Stream.of(
          Arguments.of("temperature negative", (Consumer<ModelConfigEntity>)m -> m.setTemperature(-.1)),
          Arguments.of("temperature NaN", (Consumer<ModelConfigEntity>)m -> m.setTemperature(Double.NaN)),
          Arguments.of("temperature infinity", (Consumer<ModelConfigEntity>)m -> m.setTemperature(Double.POSITIVE_INFINITY)),
          Arguments.of("topP negative", (Consumer<ModelConfigEntity>)m -> m.setTopP(-.1)),
          Arguments.of("topP > 1", (Consumer<ModelConfigEntity>)m -> m.setTopP(1.1)),
          Arguments.of("topP NaN", (Consumer<ModelConfigEntity>)m -> m.setTopP(Double.NaN)),
          Arguments.of("topP infinity", (Consumer<ModelConfigEntity>)m -> m.setTopP(Double.NEGATIVE_INFINITY)),
          Arguments.of("output zero", (Consumer<ModelConfigEntity>)m -> m.setMaxTokens(0)),
          Arguments.of("output negative", (Consumer<ModelConfigEntity>)m -> m.setMaxTokens(-1)),
          Arguments.of("input too small", (Consumer<ModelConfigEntity>)m -> m.setMaxInputTokens(12)),
          Arguments.of("input too large", (Consumer<ModelConfigEntity>)m -> m.setMaxInputTokens(20_000_001)));
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidModels")
    void createAndUpdateRejectInvalidValuesBeforeAnyWrites(String name, Consumer<ModelConfigEntity> invalid) {
        var mapper = mock(ModelConfigMapper.class); var events = mock(ApplicationEventPublisher.class);
        var service = new ModelConfigService(mapper,events,mock(ModelCapabilityService.class));
        var existing = model(); when(mapper.selectById(1L)).thenReturn(existing);
        var input = model(); input.setIsDefault(true); invalid.accept(input);
        assertThrows(MateClawException.class,() -> service.createModel(input));
        assertThrows(MateClawException.class,() -> service.updateModel(input));
        verify(mapper,never()).insert(any(ModelConfigEntity.class));
        verify(mapper,never()).updateById(any(ModelConfigEntity.class));
        verifyNoInteractions(events);
    }
    @Test void validBoundariesAndDocumentedDefaultSentinelsRemainCompatible() {
        var mapper = mock(ModelConfigMapper.class); when(mapper.selectCount(any())).thenReturn(1L);
        var service = new ModelConfigService(mapper,mock(ApplicationEventPublisher.class),mock(ModelCapabilityService.class));
        for (double topP : new double[]{0,1}) {
            var input = model(); input.setTemperature(3d); input.setTopP(topP); input.setMaxTokens(1);
            input.setMaxInputTokens(topP==0 ? 1024 : 20_000_000);
            assertDoesNotThrow(() -> service.createModel(input));
        }
        var defaults = model(); defaults.setMaxInputTokens(-1); defaults.setRequestTimeoutSeconds(-1);
        service.createModel(defaults);
        assertEquals(0,defaults.getMaxInputTokens());
        assertEquals(-1,defaults.getRequestTimeoutSeconds());
        assertNull(defaults.getMaxTokens()); assertNull(defaults.getTemperature());
    }
}
