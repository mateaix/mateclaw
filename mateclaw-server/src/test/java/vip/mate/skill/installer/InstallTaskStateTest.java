package vip.mate.skill.installer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.installer.model.InstallTask;
import static org.junit.jupiter.api.Assertions.*;
class InstallTaskStateTest {
    @Test void cancelledTaskCannotBeRestartedOrOverwrittenByLateWorkerResults() {
        var task = InstallTask.create("id","url");
        task.markCancelled(); task.markInstalling(); task.markCompleted(null); task.markFailed("late network error");
        assertEquals(InstallTask.InstallStatus.CANCELLED,task.getStatus());
    }
}
