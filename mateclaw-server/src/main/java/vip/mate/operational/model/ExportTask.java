package vip.mate.operational.model;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 运营数据导出任务——异步生成 + 一次下载模型。
 */
public class ExportTask {
    private String taskId;
    private volatile int step;
    private int total = 9;
    private volatile String status; // generating | completed | failed | timeout | oom
    private volatile Path filePath;
    private volatile long completedAt;
    private volatile String downloadToken;
    private volatile boolean downloaded;
    private volatile String errorMessage;

    public ExportTask() {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.status = "generating";
    }

    public void setCompleted(Path filePath) {
        this.status = "completed";
        this.filePath = filePath;
        this.completedAt = System.currentTimeMillis();
        this.downloadToken = UUID.randomUUID().toString().substring(0, 12);
    }

    public void setFailed(String errorMessage) {
        this.status = "failed";
        this.errorMessage = errorMessage;
    }

    public String getFileName() {
        if (filePath == null) return null;
        return filePath.getFileName().toString();
    }

    // ── Manual getters/setters (avoid Lombok/Java25 issue) ──

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public int getStep() { return step; }
    public void setStep(int step) { this.step = step; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Path getFilePath() { return filePath; }
    public void setFilePath(Path filePath) { this.filePath = filePath; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public String getDownloadToken() { return downloadToken; }
    public void setDownloadToken(String downloadToken) { this.downloadToken = downloadToken; }

    public boolean isDownloaded() { return downloaded; }
    public void setDownloaded(boolean downloaded) { this.downloaded = downloaded; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
