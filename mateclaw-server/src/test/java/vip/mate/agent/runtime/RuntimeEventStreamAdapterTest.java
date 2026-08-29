package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeEventStreamAdapterTest {
    @Test
    void adaptsProviderFluxInOrder() {
        var deltas = RuntimeEventStreamAdapter.adapt(Flux.just(
                RuntimeEvent.of("session", 0, RuntimeEventType.ASSISTANT_DELTA,
                        null, Map.of("delta", "a")),
                RuntimeEvent.of("session", 1, RuntimeEventType.THINKING_DELTA,
                        null, Map.of("delta", "b")))).collectList().block();

        assertEquals(2, deltas.size());
        assertEquals("a", deltas.get(0).content());
        assertEquals("b", deltas.get(1).thinking());
    }
}
