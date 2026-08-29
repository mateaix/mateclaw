package vip.mate.interop.a2a;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;

@Service
@RequiredArgsConstructor
public class DefaultA2aExecutionBridge implements A2aExecutionBridge {

    private final AgentService agentService;

    @Override
    public ExecutionResult executeBlocking(A2aExecutionRequest request) {
        ChatOrigin origin = ChatOrigin.web(
                request.contextId(),
                request.username(),
                request.workspaceId(),
                null,
                null,
                request.userId()
        );
        AgentService.ChatResult result = agentService.chatWithUsage(
                request.agentId(),
                request.message(),
                request.contextId(),
                origin
        );
        return new ExecutionResult(result.content(), true);
    }
}
