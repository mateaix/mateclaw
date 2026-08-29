package vip.mate.agent.runtime.contract;

public record RuntimeValidation(boolean valid, String code, String message) {
    public static RuntimeValidation success() {
        return new RuntimeValidation(true, null, null);
    }

    public static RuntimeValidation invalid(String code, String message) {
        return new RuntimeValidation(false, code, message);
    }
}
