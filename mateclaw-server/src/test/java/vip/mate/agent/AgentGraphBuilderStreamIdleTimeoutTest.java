package vip.mate.agent;

import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelConfigEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGraphBuilderStreamIdleTimeoutTest {

    @Test
    void normalizesStreamIdleTimeoutOverrides() {
        assertEquals(180, AgentGraphBuilder.resolveStreamIdleTimeoutSeconds(null));
        assertEquals(180, AgentGraphBuilder.resolveStreamIdleTimeoutSeconds(modelConfig(null)));
        assertEquals(180, AgentGraphBuilder.resolveStreamIdleTimeoutSeconds(modelConfig(0)));
        assertEquals(180, AgentGraphBuilder.resolveStreamIdleTimeoutSeconds(modelConfig(-30)));
        assertEquals(600, AgentGraphBuilder.resolveStreamIdleTimeoutSeconds(modelConfig(600)));
    }

    private static ModelConfigEntity modelConfig(Integer requestTimeoutSeconds) {
        ModelConfigEntity modelConfig = new ModelConfigEntity();
        modelConfig.setRequestTimeoutSeconds(requestTimeoutSeconds);
        return modelConfig;
    }
}
