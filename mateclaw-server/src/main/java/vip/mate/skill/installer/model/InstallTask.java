package vip.mate.skill.installer.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 安装任务状态
 *
 * @author MateClaw Team
 */
@Data
public class InstallTask {

    private String taskId;
    private String bundleUrl;
    @lombok.Setter(lombok.AccessLevel.NONE)
    private volatile InstallStatus status;
    private Long workspaceId;
    private volatile String error;
    private volatile InstallResult result;
    private LocalDateTime createdAt;
    private volatile LocalDateTime updatedAt;

    /** 取消标志 */
    @lombok.Setter(lombok.AccessLevel.NONE)
    private volatile boolean cancelRequested;
    @lombok.Setter(lombok.AccessLevel.NONE)
    private volatile boolean cancellable = true;

    public enum InstallStatus {
        PENDING,
        INSTALLING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static InstallTask create(String taskId, String bundleUrl) {
        InstallTask task = new InstallTask();
        task.setTaskId(taskId);
        task.setBundleUrl(bundleUrl);
        task.status = InstallStatus.PENDING;
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    public synchronized boolean tryStart() {
        if (status != InstallStatus.PENDING) return false;
        status = InstallStatus.INSTALLING; updatedAt = LocalDateTime.now(); return true;
    }
    public synchronized void markInstalling() { tryStart(); }

    /** Cancellation is accepted only before the first filesystem/DB mutation. */
    public synchronized boolean requestCancel() {
        if (status == InstallStatus.CANCELLED) return true;
        if (!cancellable || status != InstallStatus.PENDING && status != InstallStatus.INSTALLING) return false;
        cancelRequested = true; cancellable = false;
        status = InstallStatus.CANCELLED; updatedAt = LocalDateTime.now(); return true;
    }
    public synchronized boolean beginCommit() {
        if (status != InstallStatus.INSTALLING || cancelRequested) return false;
        cancellable = false; updatedAt = LocalDateTime.now(); return true;
    }
    public synchronized void markCompleted(InstallResult result) {
        if (status != InstallStatus.INSTALLING || cancelRequested) return;
        this.status = InstallStatus.COMPLETED; this.result = result;
        this.cancellable = false; this.updatedAt = LocalDateTime.now();
    }
    public synchronized void markFailed(String error) {
        if (status == InstallStatus.CANCELLED || status == InstallStatus.COMPLETED || status == InstallStatus.FAILED) return;
        this.status = InstallStatus.FAILED; this.error = error;
        this.cancellable = false; this.updatedAt = LocalDateTime.now();
    }
    public synchronized void markCancelled() { requestCancel(); }
}
