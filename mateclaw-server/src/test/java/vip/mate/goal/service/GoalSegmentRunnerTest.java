package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.GoalContinuationContext;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.ConversationTurnGate;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.SegmentOutcome;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalSegmentRunnerTest {
    AgentService agents=mock(AgentService.class);
    ConversationService conversations=mock(ConversationService.class);
    ApprovalWorkflowService approvals=mock(ApprovalWorkflowService.class);
    ChatStreamTracker streams=new ChatStreamTracker(new ObjectMapper());
    ConversationInputQueueStore inputQueue=mock(ConversationInputQueueStore.class);
    ConcurrentLinkedQueue<ConversationInputQueueStore.QueuedInput> durableInputs=new ConcurrentLinkedQueue<>();
    java.util.concurrent.atomic.AtomicLong inputIds=new java.util.concurrent.atomic.AtomicLong();
    ConversationTurnGate gate=new ConversationTurnGate();
    GoalEntity goal=new GoalEntity();
    GoalSegmentRunner runner=new GoalSegmentRunner(agents,conversations,approvals,streams,new ObjectMapper(),gate,inputQueue);

    @BeforeEach void setup() {
        goal.setId(1L);goal.setConversationId("conv");goal.setAgentId(2L);goal.setWorkspaceId(3L);goal.setCreatedBy("alice");
        ConversationEntity conv=new ConversationEntity();
        conv.setConversationId("conv");conv.setAgentId(2L);conv.setWorkspaceId(3L);conv.setUsername("alice");
        when(conversations.findByConversationId("conv")).thenReturn(conv);
        AgentEntity agent=new AgentEntity();agent.setEnabled(true);agent.setRuntimeType("native");
        when(agents.getAgent(2L)).thenReturn(agent);
        when(inputQueue.enqueue(anyString(),anyLong(),anyString(),anyString(),nullable(List.class),any()))
                .thenAnswer(inv -> {
                    var now=LocalDateTime.now();
                    var input=new ConversationInputQueueStore.QueuedInput(inputIds.incrementAndGet(),
                            inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3),
                            inv.getArgument(4),"queued",null,null,null,now,now);
                    durableInputs.add(input);
                    return input;
                });
        when(inputQueue.claimNext(anyString(),anyString(),any())).thenAnswer(inv -> {
            var input=durableInputs.poll();
            if(input==null) return java.util.Optional.empty();
            return java.util.Optional.of(new ConversationInputQueueStore.QueuedInput(input.id(),input.conversationId(),
                    input.agentId(),input.createdBy(),input.message(),input.contentParts(),"claimed",
                    inv.getArgument(1),input.persistedMessageId(),null,input.createdAt(),LocalDateTime.now()));
        });
        when(inputQueue.bindMessage(anyLong(),anyString(),anyLong(),any())).thenReturn(true);
        when(inputQueue.consume(anyLong(),anyString(),any())).thenReturn(true);
        when(inputQueue.release(anyLong(),anyString(),any())).thenReturn(true);
        when(inputQueue.countQueued(anyString())).thenAnswer(inv -> durableInputs.size());
        MessageEntity savedUser=new MessageEntity();savedUser.setId(77L);
        when(conversations.saveMessage(eq("conv"),eq("user"),anyString(),nullable(List.class),eq("queued")))
                .thenReturn(savedUser);
    }

    private void enqueue(String message,List<MessageContentPart> parts) {
        inputQueue.enqueue("conv",2L,"alice",message,parts,LocalDateTime.now());
    }

    @Test void persistsStreamedResultAndUsage() {
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.just(new AgentService.StreamDelta("actual output",null),
                        AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal"))));
        var result=runner.run(goal,"continue",false);
        assertEquals("normal",result.finishReason());
        verify(conversations).saveMessage(eq("conv"),eq("assistant"),eq("actual output"),anyList(),eq("completed"),
                anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyString(),anyString());
        assertFalse(streams.isRunning("conv"));
        assertNotNull(gate.tryAcquire("conv"));
    }

    @Test void rejectsChangedConversationIdentityBeforeAnyModelCall() {
        goal.setWorkspaceId(99L);
        assertThrows(IllegalStateException.class,()->runner.run(goal,"continue",false));
        verify(agents,never()).chatStructuredStream(any(),any(),any(),any(),any(),any());
    }

    @Test void busyConversationIsNotRegisteredOrMutated() {
        var user=gate.tryAcquire("conv");
        assertThrows(vip.mate.exception.MateClawException.class,()->runner.run(goal,"continue",false));
        verifyNoInteractions(conversations);
        user.close();
    }

    @Test void drainsUserInputAcceptedDuringBackgroundTurn() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenAnswer(inv -> Flux.defer(() -> {
                    boolean autonomous = calls.incrementAndGet()==1;
                    assertTrue(GoalContinuationContext.active());
                    assertEquals(autonomous, GoalContinuationContext.explicitPrompt());
                    if(autonomous) enqueue("new user instruction",null);
                    return Flux.just(new AgentService.StreamDelta("output",null),
                            AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal")));
                }));
        runner.run(goal,"continue",false);
        assertEquals(2,calls.get());
        assertEquals(0,inputQueue.countQueued("conv"));
        verify(agents).chatStructuredStream(eq(2L),eq("new user instruction"),eq("conv"),eq("alice"),isNull(),any());
        verify(conversations).saveMessage("conv","user","new user instruction",null,"queued");
    }

    @Test void workerCancellationPersistsPartialEvidenceAndReleasesAdmission() throws Exception {
        var subscribed=new java.util.concurrent.CountDownLatch(1);
        var toolCancelled=new java.util.concurrent.atomic.AtomicBoolean();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.concat(Flux.just(new AgentService.StreamDelta("partial evidence",null)),
                        Flux.<AgentService.StreamDelta>never().doOnSubscribe(s -> {
                            streams.registerCancellationHook("conv",()->toolCancelled.set(true));
                            subscribed.countDown();
                        })));
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var result=new java.util.concurrent.atomic.AtomicReference<SegmentOutcome>();
        Thread worker=Thread.ofVirtual().start(() -> {
            try { result.set(runner.run(goal,"continue",false)); } catch(Throwable error) { failure.set(error); }
        });
        assertTrue(subscribed.await(3,java.util.concurrent.TimeUnit.SECONDS));
        runner.cancel(1L);
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertTrue(toolCancelled.get(),"escaped tool process must be cancelled as well as the stream");
        // Cancellation may finish the Flux before the worker receives its interrupt.
        // Both paths must preserve evidence and report a stopped/interrupted outcome.
        if (failure.get()==null) assertEquals("stopped",result.get().finishReason());
        verify(conversations).saveMessage(eq("conv"),eq("assistant"),eq("partial evidence"),anyList(),
                argThat(status -> "interrupted".equals(status) || "stopped".equals(status)),
                anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyString(),anyString());
        assertNotNull(gate.tryAcquire("conv"));
    }

    @Test void cancellationDoesNotInterruptDatabasePersistence() throws Exception {
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.just(new AgentService.StreamDelta("checkpoint", null)));
        when(conversations.saveMessage(eq("conv"),eq("assistant"),anyString(),anyList(),anyString(),
                anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyString(),anyString()))
                .thenAnswer(inv -> {
                    saving.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { interrupted.set(true); }
                    return null;
                });
        Thread worker = Thread.ofVirtual().start(() -> runner.run(goal,"continue",false));
        assertTrue(saving.await(3,TimeUnit.SECONDS));
        runner.cancel(1L);
        release.countDown();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertFalse(interrupted.get(), "cancellation must not close embedded database channels by interrupting I/O");
    }

    @Test void shutdownPersistsQueuedInputWithAttachments() throws Exception {
        var ready = new CountDownLatch(1);
        MessageContentPart attachment = new MessageContentPart();
        attachment.setType("file");
        attachment.setPath("test-evidence.txt");
        var parts = List.of(attachment);
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.<AgentService.StreamDelta>never().doOnSubscribe(s -> ready.countDown()));
        Thread worker = Thread.ofVirtual().start(() -> runner.run(goal,"continue",false));
        assertTrue(ready.await(3,TimeUnit.SECONDS));
        enqueue("accepted steering",parts);
        runner.cancelAll();
        worker.join(3000);
        assertFalse(worker.isAlive());
        verify(conversations,never()).saveMessage("conv","user","accepted steering",parts,"queued");
        assertEquals(1,inputQueue.countQueued("conv"));
    }

    @Test void shutdownRejectsLateWorkerAdmissionWithoutStartingModel() {
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.just(AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal"))));
        runner.cancelAll();
        assertEquals("stopped", runner.run(goal,"continue",false).finishReason());
        verify(agents,never()).chatStructuredStream(any(),any(),any(),any(),any(),any());
    }

    @Test void externalInterruptIsRestoredOnlyAfterCheckpointPersistence() throws Exception {
        var ready = new CountDownLatch(1);
        var persisted = new AtomicBoolean();
        var interruptRestored = new AtomicBoolean();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.concat(Flux.just(new AgentService.StreamDelta("partial",null)),
                        Flux.<AgentService.StreamDelta>never().doOnSubscribe(s -> ready.countDown())));
        when(conversations.saveMessage(eq("conv"),eq("assistant"),anyString(),anyList(),anyString(),
                anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyString(),anyString()))
                .thenAnswer(inv -> {
                    assertFalse(Thread.currentThread().isInterrupted());
                    persisted.set(true);
                    return null;
                });
        Thread worker = Thread.ofVirtual().start(() -> {
            try { runner.run(goal,"continue",false); }
            catch (IllegalStateException expected) { interruptRestored.set(Thread.currentThread().isInterrupted()); }
        });
        assertTrue(ready.await(3,TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertTrue(persisted.get());
        assertTrue(interruptRestored.get());
    }

    @Test void permanentFailurePersistsAcceptedQueuedInput() {
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenReturn(Flux.defer(() -> {
                    enqueue("user instruction",null);
                    return Flux.error(new IllegalArgumentException("bad config"));
                }));
        assertThrows(IllegalArgumentException.class,()->runner.run(goal,"continue",false));
        verify(conversations,never()).saveMessage("conv","user","user instruction",null,"queued");
        assertEquals(1,inputQueue.countQueued("conv"));
    }

    @Test void userSteeringPreservesInterruptedStatusThenRunsQueuedInput() throws Exception {
        var subscribed=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenAnswer(inv -> calls.incrementAndGet()==1
                        ? Flux.concat(Flux.just(new AgentService.StreamDelta("partial",null)),
                            Flux.<AgentService.StreamDelta>never().doOnSubscribe(s -> subscribed.countDown()))
                        : Flux.just(new AgentService.StreamDelta("answer to steering",null)));
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=Thread.ofVirtual().start(() -> {
            try { runner.run(goal,"continue",false); } catch(Throwable error) { failure.set(error); }
        });
        assertTrue(subscribed.await(3,java.util.concurrent.TimeUnit.SECONDS));
        enqueue("new instruction",null);
        assertTrue(streams.requestInterrupt("conv","",2L,false));
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(2,calls.get());
        verify(conversations).saveMessage(eq("conv"),eq("assistant"),eq("partial"),anyList(),eq("interrupted"),
                anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyString(),anyString(),anyString());
    }

    @Test void goalCancellationDoesNotKillQueuedInteractiveWork() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var finish=reactor.core.publisher.Sinks.<AgentService.StreamDelta>one();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenAnswer(inv -> {
                    if(calls.incrementAndGet()==1) {
                        enqueue("new question",null);
                        return Flux.just(AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal")));
                    }
                    return finish.asMono().flux().doOnSubscribe(s -> entered.countDown());
                });
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=Thread.ofVirtual().start(() -> {
            try { runner.run(goal,"continue",false); } catch(Throwable error) { failure.set(error); }
        });
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        runner.cancel(1L);
        finish.tryEmitValue(AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal")));
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
    }

    @Test void explicitStopLatchesAcrossQueuedInputRegistrationGap() throws Exception {
        var saving=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(agents.chatStructuredStream(eq(2L),anyString(),eq("conv"),eq("alice"),isNull(),any()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    enqueue("new question",null);
                    return Flux.just(AgentService.StreamDelta.event("finish_reason",Map.of("reason","normal")));
                });
        when(conversations.saveMessage("conv","user","new question",null,"queued")).thenAnswer(inv -> {
            saving.countDown();
            boolean interrupted=false;
            while(true) {
                try { if(release.await(3,java.util.concurrent.TimeUnit.SECONDS)) break; else throw new AssertionError("release timeout"); }
                catch(InterruptedException ignored) { interrupted=true; }
            }
            if(interrupted) Thread.currentThread().interrupt();
            return null;
        });
        Thread worker=Thread.ofVirtual().start(() -> {
            try { runner.run(goal,"continue",false); } catch(RuntimeException expected) { }
        });
        assertTrue(saving.await(3,java.util.concurrent.TimeUnit.SECONDS));
        runner.stopConversation("conv");
        release.countDown();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertEquals(1,calls.get(),"no queued model request may start after explicit Stop");
    }
}
