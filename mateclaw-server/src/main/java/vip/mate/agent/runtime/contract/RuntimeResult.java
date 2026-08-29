package vip.mate.agent.runtime.contract;

public record RuntimeResult(Status status, String answer, String errorCode, String errorMessage) {
    public enum Status { COMPLETED, FAILED, CANCELLED }

    public RuntimeResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (status == Status.COMPLETED && (errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("completed result cannot contain an error");
        }
    }

    public static RuntimeResult completed(String answer) {
        return new RuntimeResult(Status.COMPLETED, answer, null, null);
    }

    public static RuntimeResult failed(String code, String message) {
        return new RuntimeResult(Status.FAILED, null, code, message);
    }

    public static RuntimeResult cancelled() {
        return new RuntimeResult(Status.CANCELLED, null, null, null);
    }
}
