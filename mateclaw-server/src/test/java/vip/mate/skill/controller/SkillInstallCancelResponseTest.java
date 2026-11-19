package vip.mate.skill.controller;
import org.junit.jupiter.api.Test;
import vip.mate.skill.installer.SkillInstaller;
import vip.mate.skill.runtime.SkillFrontmatterParser;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillInstallCancelResponseTest {
    @Test void rejectedCancellationDoesNotReportSuccess() {
        var installer = mock(SkillInstaller.class);
        var controller = new SkillInstallController(installer,new SkillFrontmatterParser());
        when(installer.cancelTask("committing")).thenReturn(false);
        assertEquals(409,controller.cancel("committing").getCode());
        when(installer.cancelTask("downloading")).thenReturn(true);
        assertEquals(200,controller.cancel("downloading").getCode());
    }
}
