package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ModelConfigService#updateModelContextWindow} — the operator
 * override behind the model-management UI's context-window field.
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigServiceContextWindowTest {

    @Mock
    private ModelConfigMapper modelConfigMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ModelConfigService service;

    private ModelConfigEntity existingModel() {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setId(1L);
        m.setProvider("deepseek");
        m.setModelName("deepseek-v4-pro");
        m.setMaxInputTokens(0);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void mapperReturns(ModelConfigEntity entity) {
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
    }

    @Test
    @DisplayName("a positive value is persisted on the model row")
    void setsWindow() {
        ModelConfigEntity model = existingModel();
        mapperReturns(model);

        service.updateModelContextWindow("deepseek", "deepseek-v4-pro", 262_144);

        assertEquals(262_144, model.getMaxInputTokens());
        verify(modelConfigMapper).updateById(model);
    }

    @Test
    @DisplayName("null clears the override back to 'let the server decide'")
    void clearsWindow() {
        ModelConfigEntity model = existingModel();
        model.setMaxInputTokens(262_144);
        mapperReturns(model);

        service.updateModelContextWindow("deepseek", "deepseek-v4-pro", null);

        assertEquals(0, model.getMaxInputTokens());
        verify(modelConfigMapper).updateById(model);
    }

    @Test
    @DisplayName("out-of-range values are rejected instead of persisted")
    void rejectsOutOfRange() {
        ModelConfigEntity model = existingModel();
        mapperReturns(model);

        assertThrows(MateClawException.class,
                () -> service.updateModelContextWindow("deepseek", "deepseek-v4-pro", 12));
        verify(modelConfigMapper, never()).updateById(any(ModelConfigEntity.class));
    }

    @Test
    @DisplayName("an unknown model is an error, not a silent no-op")
    void rejectsUnknownModel() {
        mapperReturns(null);

        assertThrows(MateClawException.class,
                () -> service.updateModelContextWindow("deepseek", "nope", 128_000));
        verify(modelConfigMapper, never()).updateById(any(ModelConfigEntity.class));
    }
}
