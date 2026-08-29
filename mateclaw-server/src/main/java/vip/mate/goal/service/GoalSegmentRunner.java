package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.GoalContinuationContext;
import vip.mate.agent.runtime.ConversationTurnGate;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.AgentStreamAccumulator;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.SegmentOutcome;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.Map;
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** Runs and persists one ordinary graph segment without an HTTP subscriber. */
@Component
public class GoalSegmentRunner {
    private final AgentService agents;
    private final ConversationService conversations;
    private final ApprovalWorkflowService approvals;
    private final ChatStreamTracker streams;
    private final ObjectMapper mapper;
    private final ConversationTurnGate gate;
    private final ConversationInputQueueStore inputQueue;
    private final ConcurrentHashMap<Long,Worker> workers=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private static final class Worker {
        final String conversationId;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicBoolean interrupted=new AtomicBoolean();
        final AtomicReference<ChatStreamTracker.RunHandle> handle=new AtomicReference<>();
        volatile boolean interactive;
        Worker(String conversationId) { this.conversationId=conversationId; }
    }
    @org.springframework.beans.factory.annotation.Autowired
    private GoalService goals;
    @org.springframework.beans.factory.annotation.Autowired
    private GoalRunCoordinator coordinator;

    public GoalSegmentRunner(AgentService agents, ConversationService conversations,
            ApprovalWorkflowService approvals, ChatStreamTracker streams, ObjectMapper mapper,
            ConversationTurnGate gate, ConversationInputQueueStore inputQueue) {
        this.agents=agents;this.conversations=conversations;this.approvals=approvals;
        this.streams=streams;this.mapper=mapper;this.gate=gate;this.inputQueue=inputQueue;
    }

    private record SegmentResult(String finishReason, boolean awaitingApproval, boolean evaluationUnavailable) {}

    /** Cancel this worker only, never a newer conversation generation or a user turn. */
    public void cancel(Long goalId) {
        Worker worker=workers.get(goalId);
        if (worker!=null && !worker.interactive) {
            cancelWorker(worker);
        }
    }

    /** User Stop applies even after the goal completed and queued interactive work took over. */
    public void stopConversation(String conversationId) {
        workers.values().stream().filter(w -> Objects.equals(w.conversationId,conversationId)).forEach(this::cancelWorker);
    }

    public void cancelAll() {
        closing=true;
        workers.values().forEach(this::cancelWorker);
    }

    private void cancelWorker(Worker worker) {
        worker.cancelled.set(true);
        streams.cancelRun(worker.handle.get());
        // Stream disposal releases the completion latch. Never interrupt this
        // worker: it also performs JDBC I/O on shared embedded database channels.
    }

    public SegmentOutcome run(GoalEntity goal, String prompt, boolean recovered) {
        return run(goal,prompt,recovered,null);
    }

    public SegmentOutcome run(GoalRunCoordinator.ClaimedRun claimed,String prompt,boolean recovered) {
        return run(claimed.goal(),prompt,recovered,claimed);
    }

    private SegmentOutcome run(GoalEntity goal,String prompt,boolean recovered,
                               GoalRunCoordinator.ClaimedRun claimedRun) {
        String convId=goal.getConversationId();
        var permit=gate.tryAcquire(convId);
        if (permit==null) throw new MateClawException("err.agent.conversation_busy",409,"Conversation is busy");
        Worker worker=new Worker(convId);
        AtomicReference<ConversationInputQueueStore.QueuedInput> claimedInput=new AtomicReference<>();
        try {
            workers.put(goal.getId(),worker);
            // Register before checking the shutdown fence so cancellation cannot miss us.
            if (closing) {
                worker.cancelled.set(true);
                return new SegmentOutcome.Cancelled("stopped");
            }
            var conv=conversations.findByConversationId(convId);
            if (conv==null || !Objects.equals(conv.getWorkspaceId(),goal.getWorkspaceId())
                    || !Objects.equals(conv.getAgentId(),goal.getAgentId())
                    || !Objects.equals(conv.getUsername(),goal.getCreatedBy())
                    || Integer.valueOf(1).equals(conv.getDeleted()) || Integer.valueOf(1).equals(conv.getArchived())) {
                throw new IllegalStateException("Goal conversation identity changed or conversation unavailable");
            }
            var agent=agents.getAgent(goal.getAgentId());
            if (agent==null || Boolean.FALSE.equals(agent.getEnabled())
                    || (agent.getRuntimeType()!=null && !"native".equals(agent.getRuntimeType()))) {
                throw new IllegalStateException("Goal requires an enabled native runtime with goal evaluation");
            }
            if (approvals.findPendingByConversation(convId)!=null) return new SegmentOutcome.AwaitApproval("approval_required");
            if (streams.isRunning(convId)) {
                throw new MateClawException("err.agent.conversation_busy",409,"Conversation has pending input");
            }
            String guidance=recovered ? "The previous execution was interrupted by a runtime restart. "
                    + "Inspect the workspace, progress ledger and existing async handles before acting. "
                    + "Do not replay side effects whose outcome is unknown; request review if their outcome cannot be verified.\n" : "";
            ChatOrigin origin=ChatOrigin.web(convId,goal.getCreatedBy(),goal.getWorkspaceId(),null).withAgent(goal.getAgentId());
            SegmentResult result;
            ConversationInputQueueStore.QueuedInput queued=claimNextInput(convId,claimedRun);
            do {
                String input=guidance+prompt;
                if (queued!=null) {
                    claimedInput.set(queued);
                    if (queued.agentId()!=null && !queued.agentId().equals(goal.getAgentId())) {
                        inputQueue.release(queued.id(),queued.claimedByAttemptId(),LocalDateTime.now());
                        claimedInput.set(null);
                        throw new IllegalStateException("Queued input targets a different agent; user review required");
                    }
                    Long originMessageId=queued.persistedMessageId();
                    if (originMessageId==null) {
                        var saved=conversations.saveMessage(convId,"user",queued.message(),queued.contentParts(),"queued");
                        originMessageId=saved==null ? null : saved.getId();
                        if (originMessageId==null || !inputQueue.bindMessage(queued.id(),queued.claimedByAttemptId(),
                                originMessageId,LocalDateTime.now())) {
                            throw new IllegalStateException("Queued input could not be bound to its persisted message");
                        }
                    }
                    if (!inputQueue.consume(queued.id(),queued.claimedByAttemptId(),LocalDateTime.now())) {
                        throw new IllegalStateException("Queued input claim was lost before execution");
                    }
                    claimedInput.set(null);
                    worker.interactive=true;
                    origin=origin.withOriginMessageId(originMessageId);
                    input=queuedPrompt(queued);
                    streams.broadcastObject(convId,"queued_input_started",Map.of("conversationId",convId,"message",input));
                } else if (goals!=null && goals.getById(goal.getId()).getStatus()!=vip.mate.goal.model.GoalStatus.ACTIVE) {
                    return new SegmentOutcome.Cancelled("stopped");
                }
                result=runSegment(goal,input,origin,permit,worker,claimedRun);
                if (result.awaitingApproval()) return new SegmentOutcome.AwaitApproval("approval_required");
                if ("stopped".equals(result.finishReason())) return new SegmentOutcome.Cancelled("stopped");
                queued=claimNextInput(convId,claimedRun);
            } while (queued!=null);
            if(result.evaluationUnavailable()) return new SegmentOutcome.Retry("evaluation","evaluation_unavailable");
            if("error_fallback".equals(result.finishReason())) {
                return new SegmentOutcome.Blocked("graph","graph_error_requires_review");
            }
            return new SegmentOutcome.Continue(result.finishReason()==null ? "unfinished" : result.finishReason());
        } catch (RuntimeException error) {
            if (Thread.interrupted()) worker.interrupted.set(true);
            streams.broadcastObject(convId,"warning",Map.of("message",
                    "Goal execution interrupted. Durable queued input remains available for recovery."));
            throw error;
        } finally {
            try {
                ConversationInputQueueStore.QueuedInput claimed=claimedInput.getAndSet(null);
                if (claimed!=null) {
                    inputQueue.release(claimed.id(),claimed.claimedByAttemptId(),LocalDateTime.now());
                }
            } finally {
                workers.remove(goal.getId(),worker);
                permit.close();
                if (worker.interrupted.get()) Thread.currentThread().interrupt();
            }
        }
    }

    private SegmentResult runSegment(GoalEntity goal, String input, ChatOrigin origin,
                                     ConversationTurnGate.Permit permit, Worker worker,
                                     GoalRunCoordinator.ClaimedRun claimedRun) {
        String convId=goal.getConversationId();
        var handle=streams.register(convId);
        worker.handle.set(handle);
        streams.incrementFlux(convId);
        AgentStreamAccumulator accumulator=new AgentStreamAccumulator(mapper,new AgentStreamAccumulator.Sink() {
            @Override public void broadcast(String id,String name,Object payload) { streams.broadcastObject(id,name,payload); }
            @Override public void updatePhase(String id,String phase) { streams.updatePhase(id,phase); }
        });
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicBoolean evaluationUnavailable=new AtomicBoolean();
        AtomicBoolean persisted=new AtomicBoolean();
        CountDownLatch finished=new CountDownLatch(1);
        Disposable subscription=null;
        try {
            if (worker.cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if(claimedRun!=null && !checkpoint(claimedRun,"safe","provider_started",null)) {
                throw new IllegalStateException("Goal attempt lost its execution fence");
            }
            conversations.updateStreamStatus(convId,"running");
            streams.broadcastObject(convId,"message_start",Map.of("role","assistant","trigger","goal"));
            subscription=gate.withPermit(permit,() -> GoalContinuationContext.call(!worker.interactive, () ->
                    reactor.core.publisher.Flux.defer(() -> {
                        if (worker.cancelled.get()) return reactor.core.publisher.Flux.empty();
                        return agents.chatStructuredStream(goal.getAgentId(),input,
                            convId,goal.getCreatedBy(),null,origin)
                    .doOnNext(delta -> {
                        accumulator.accept(delta,convId);
                        if(claimedRun!=null && "tool_call_started".equals(delta.eventType())) {
                            checkpoint(claimedRun,"uncertain","tool_started",null);
                        } else if(claimedRun!=null && "tool_call_completed".equals(delta.eventType())) {
                            checkpoint(claimedRun,"resolved","tool_completed",null);
                        }
                        if ("goal_evaluated".equals(delta.eventType()) && delta.eventData()!=null
                                && (Boolean.TRUE.equals(delta.eventData().get("skipped"))
                                || "fallback".equals(delta.eventData().get("decision")))) evaluationUnavailable.set(true);
                    });
                    })
                    .doOnSubscribe(s -> streams.setDisposable(handle, s::cancel))
                    .doFinally(signal -> finished.countDown())
                    .subscribe(delta -> {},failure::set)));
            streams.setDisposable(handle,subscription);
            finished.await();
            String reason=streams.isStopRequested(convId)
                    ? streams.getInterruptType(convId)==ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP
                        ? "interrupted" : "stopped"
                    : accumulator.getFinishReason();
            String status="stopped".equals(reason) ? "stopped" : "interrupted".equals(reason) ? "interrupted" : accumulator.isAwaitingApproval()
                    ? "awaiting_approval" : failure.get()!=null || "error_fallback".equals(reason) ? "error" : "completed";
            MessageEntity saved=persist(convId,accumulator,status);
            if(claimedRun!=null && !checkpoint(claimedRun,"resolved","message_saved",
                    saved==null ? null : saved.getId())) {
                throw new IllegalStateException("Goal attempt lost its checkpoint fence");
            }
            persisted.set(true);
            streams.broadcastObject(convId,"message_complete",Map.of("status",status,"trigger","goal"));
            if (failure.get()!=null && !"stopped".equals(reason)) {
                throw failure.get() instanceof RuntimeException runtime ? runtime : new RuntimeException(failure.get());
            }
            return new SegmentResult(reason,accumulator.isAwaitingApproval(),evaluationUnavailable.get());
        } catch (InterruptedException interrupted) {
            worker.interrupted.set(true);
            throw new IllegalStateException("Goal worker interrupted; recover from persisted evidence",interrupted);
        } finally {
            if (Thread.interrupted()) worker.interrupted.set(true);
            if (worker.cancelled.get() || worker.interrupted.get()) streams.cancelRun(handle);
            if (subscription!=null) subscription.dispose();
            try {
                if (!persisted.get()) persist(convId,accumulator,"interrupted");
            } finally {
                conversations.updateStreamStatus(convId,"idle");
                streams.broadcastObject(convId,"done",Map.of("status","segment_finished"));
                streams.complete(handle);
            }
        }
    }

    private MessageEntity persist(String convId,AgentStreamAccumulator accumulator,String status) {
        return conversations.saveMessage(convId,"assistant",accumulator.getContent(),accumulator.toAssistantParts(),status,
                accumulator.getPromptTokens(),accumulator.getCompletionTokens(),accumulator.getCacheReadTokens(),
                accumulator.getCacheWriteTokens(),accumulator.getReasoningTokens(),accumulator.getRuntimeModelName(),
                accumulator.getRuntimeProviderId(),accumulator.toMetadataJson());
    }

    private boolean checkpoint(GoalRunCoordinator.ClaimedRun run,String safety,String type,Long messageId) {
        if(coordinator==null) return true;
        return coordinator.checkpoint(run,safety,type,messageId,LocalDateTime.now());
    }

    private ConversationInputQueueStore.QueuedInput claimNextInput(String conversationId,
                                                                   GoalRunCoordinator.ClaimedRun claimedRun) {
        String claimant=claimedRun==null ? UUID.randomUUID().toString() : claimedRun.attempt().id();
        return inputQueue.claimNext(conversationId,claimant,LocalDateTime.now()).orElse(null);
    }

    private String queuedPrompt(ConversationInputQueueStore.QueuedInput queued) {
        if (queued.contentParts()==null || queued.contentParts().isEmpty()) return queued.message();
        var message=new vip.mate.workspace.conversation.model.MessageEntity();
        message.setContent(queued.message());
        try { message.setContentParts(mapper.writeValueAsString(queued.contentParts())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid queued input",error); }
        return conversations.renderMessageContent(message,true);
    }
}
