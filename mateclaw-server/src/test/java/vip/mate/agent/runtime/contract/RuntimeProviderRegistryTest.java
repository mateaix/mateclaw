package vip.mate.agent.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeProviderRegistryTest {

    @Test
    void blankRuntimeUsesNativeProvider() {
        AgentRuntimeProvider nativeProvider = provider("native");
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry(List.of(nativeProvider, provider("dsh")));

        assertEquals(nativeProvider, registry.resolve(null));
        assertEquals(nativeProvider, registry.resolve(""));
    }

    @Test
    void unknownRuntimeIsRejected() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry(List.of(provider("native")));

        assertThrows(IllegalArgumentException.class, () -> registry.resolve("acp"));
    }

    @Test
    void duplicateRuntimeTypesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProviderRegistry(List.of(provider("dsh"), provider("dsh"))));
    }

    private static AgentRuntimeProvider provider(String type) {
        return new AgentRuntimeProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public RuntimeValidation validate(RuntimeSession session) {
                return RuntimeValidation.success();
            }

            @Override
            public RuntimeCapabilities capabilities() {
                return new RuntimeCapabilities(true, true, true, true);
            }

            @Override
            public AgentRuntimeConnection start(RuntimeSession session) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
