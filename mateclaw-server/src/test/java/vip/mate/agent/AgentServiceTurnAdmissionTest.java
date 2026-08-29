package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import vip.mate.memory.MemoryProperties;
import java.util.function.BiFunction;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTurnAdmissionTest {
    @Test void onlyInteractiveCompletionWakesApprovalWaitEvenAfterContextHasExited() {
        MemoryProperties properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        AgentService service = new AgentService(null,null,null,null,properties,null,null);
        var events = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service,"events",events);
        var source = reactor.core.publisher.Sinks.<String>empty();
        BiFunction<String,String,Flux<String>> invoke = (message,conversation) -> source.asMono().flux();
        Function<String,String> content = Function.identity();
        Flux<String> automatic = ReflectionTestUtils.invokeMethod(service,"withLifecycleFlux",1L,"a","conv",invoke,content);
        vip.mate.agent.context.GoalContinuationContext.call(automatic::subscribe);
        source.tryEmitEmpty();
        org.mockito.Mockito.verifyNoInteractions(events);

        BiFunction<String,String,Flux<String>> interactiveInvoke = (message,conversation) -> Flux.empty();
        Flux<String> interactive = ReflectionTestUtils.invokeMethod(service,"withLifecycleFlux",1L,"b","conv",interactiveInvoke,content);
        interactive.blockLast();
        org.mockito.Mockito.verify(events).publishEvent(new vip.mate.goal.service.GoalExecutionSignal.TurnFinished("conv"));
    }

    @Test void admissionIsLazySharedAndReleasedOnCancellation() {
        MemoryProperties properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        AgentService service = new AgentService(null,null,null,null,properties,null,null);
        BiFunction<String,String,Flux<String>> invoke = (message,conversation) -> Flux.never();
        Function<String,String> content = Function.identity();
        Flux<String> first = ReflectionTestUtils.invokeMethod(service,"withLifecycleFlux",1L,"a","conv",invoke,content);
        Flux<String> second = ReflectionTestUtils.invokeMethod(service,"withLifecycleFlux",1L,"b","conv",invoke,content);
        assertNotNull(first); assertNotNull(second);
        var subscription = first.subscribe();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        second.subscribe(value -> {},error::set);
        assertNotNull(error.get(),"a second turn must not execute alongside the first");
        subscription.dispose();
        error.set(null);
        var third = second.subscribe(value -> {},error::set);
        assertNull(error.get());
        third.dispose();
    }
}
