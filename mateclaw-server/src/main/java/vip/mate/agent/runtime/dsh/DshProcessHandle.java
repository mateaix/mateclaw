package vip.mate.agent.runtime.dsh;

public interface DshProcessHandle {
    boolean isAlive();

    void destroy();

    void destroyForcibly();

    boolean awaitExit(long millis);
}
