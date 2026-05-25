package vip.mate.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.trigger.scheduler.TriggerScheduler;
import vip.mate.trigger.service.TriggerService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the lamport / scheduler-sync invariants of {@link TriggerService}:
 * pattern_version must bump on every cron expression / pattern type change
 * and on every enable→disable transition; the scheduler must mirror the
 * row's enabled state. The tests rely on the scheduler's package-private
 * {@code isRegistered} accessor instead of waiting for an actual cron tick.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trigger_lifecycle_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class TriggerServiceLifecycleTest {

    @Autowired private TriggerService triggerService;
    @Autowired private TriggerMapper triggerMapper;
    @Autowired private TriggerScheduler scheduler;

    @Test
    @DisplayName("create() persists a v1 trigger and registers it with the scheduler when enabled.")
    void createRegistersEnabled() {
        TriggerEntity t = newCronTrigger("hourly", "0 0 * * * *", true);
        TriggerEntity saved = triggerService.create(t);
        assertEquals(1L, saved.getPatternVersion());
        assertTrue(scheduler.isRegistered(saved.getId()));

        // Disabled trigger row persists but does not occupy a scheduled slot.
        TriggerEntity disabled = triggerService.create(newCronTrigger("dormant", "0 0 1 * * *", false));
        assertEquals(1L, disabled.getPatternVersion());
        assertFalse(scheduler.isRegistered(disabled.getId()));
    }

    @Test
    @DisplayName("update() bumps pattern_version when the cron expression changes.")
    void updateBumpsLamportOnPatternChange() {
        TriggerEntity created = triggerService.create(newCronTrigger("flex", "0 0 * * * *", true));
        long firstVersion = created.getPatternVersion();

        created.setPatternJson("{\"cron\":\"0 30 * * * *\"}");
        TriggerEntity updated = triggerService.update(created);
        assertEquals(firstVersion + 1, updated.getPatternVersion());

        // No-op update does not bump the lamport.
        TriggerEntity reloaded = triggerMapper.selectById(updated.getId());
        TriggerEntity touched = triggerService.update(reloaded);
        assertEquals(updated.getPatternVersion(), touched.getPatternVersion());
    }

    @Test
    @DisplayName("update() flipping enabled toggles scheduler registration and bumps lamport.")
    void enableTransitionTogglesSchedulerAndBumpsLamport() {
        TriggerEntity created = triggerService.create(newCronTrigger("toggle", "0 0 * * * *", true));
        long version = created.getPatternVersion();

        created.setEnabled(false);
        TriggerEntity disabled = triggerService.update(created);
        assertEquals(version + 1, disabled.getPatternVersion());
        assertFalse(scheduler.isRegistered(disabled.getId()));

        disabled.setEnabled(true);
        TriggerEntity reEnabled = triggerService.update(disabled);
        assertEquals(version + 2, reEnabled.getPatternVersion());
        assertTrue(scheduler.isRegistered(reEnabled.getId()));
    }

    @Test
    @DisplayName("delete() removes both the row and the scheduler registration.")
    void deleteUnregistersAndRemovesRow() {
        TriggerEntity created = triggerService.create(newCronTrigger("ephemeral", "0 0 * * * *", true));
        long id = created.getId();
        triggerService.delete(id);
        assertFalse(scheduler.isRegistered(id));
        assertNull(triggerMapper.selectById(id));
    }

    private static TriggerEntity newCronTrigger(String name, String cron, boolean enabled) {
        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(99L);
        t.setName(name);
        t.setPatternType("cron");
        t.setPatternJson("{\"cron\":\"" + cron + "\"}");
        t.setTargetType("workflow");
        t.setTargetId(42L);
        t.setEnabled(enabled);
        return t;
    }
}
