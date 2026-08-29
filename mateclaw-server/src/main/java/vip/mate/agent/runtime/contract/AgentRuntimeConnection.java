package vip.mate.agent.runtime.contract;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentRuntimeConnection extends AutoCloseable {
    Flux<RuntimeEvent> prompt(String message);

    Mono<Void> cancel();

    Mono<RuntimeContextUsage> contextUsage();

    @Override
    default void close() {
        cancel().block();
    }
}
