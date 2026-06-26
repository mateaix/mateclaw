package vip.mate.operational.model;

/**
 * 导出任务并发冲突异常——{@code AtomicBoolean} 已被占用。
 */
public class ExportInProgressException extends RuntimeException {
    public ExportInProgressException(String message) {
        super(message);
    }
}
