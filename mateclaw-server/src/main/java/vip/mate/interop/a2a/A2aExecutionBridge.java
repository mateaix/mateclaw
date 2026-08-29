package vip.mate.interop.a2a;

public interface A2aExecutionBridge {

    ExecutionResult executeBlocking(A2aExecutionRequest request);

    record ExecutionResult(String text, boolean terminal) {
    }
}
