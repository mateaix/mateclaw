package vip.mate.agent.runtime.contract;

public interface AgentRuntimeProvider {
    String type();

    RuntimeValidation validate(RuntimeSession session);

    RuntimeCapabilities capabilities();

    AgentRuntimeConnection start(RuntimeSession session);
}
