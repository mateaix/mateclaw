package vip.mate.cron.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.cron.model.CronJobDTO;
import vip.mate.cron.model.DeliveryConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the delivery binding on the cron CRUD path:
 * {@code channelId} and {@code deliveryConfig} must survive a create, be
 * readable back through the list/detail queries, and be mutable through
 * {@code update()} — including clearing the binding entirely.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cron_delivery_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class CronJobDeliveryPersistenceTest {

    private static final long WORKSPACE_ID = 1L;

    @Autowired
    private CronJobService cronJobService;

    private CronJobDTO newDto(String name, Long channelId, DeliveryConfig deliveryConfig) {
        CronJobDTO dto = new CronJobDTO();
        dto.setName(name);
        dto.setCronExpression("*/5 * * * *");
        dto.setTimezone("Asia/Shanghai");
        dto.setAgentId(9001L);
        dto.setTaskType("text");
        dto.setTriggerMessage("ping");
        dto.setEnabled(false);
        dto.setChannelId(channelId);
        dto.setDeliveryConfig(deliveryConfig);
        return dto;
    }

    @Test
    @DisplayName("create persists the delivery binding and the read path returns it")
    void createPersistsDeliveryBinding() {
        CronJobDTO created = cronJobService.create(
                newDto("delivery-create", 7001L,
                        new DeliveryConfig("target-1", null, null, "user-1", Boolean.FALSE)),
                WORKSPACE_ID);

        CronJobDTO loaded = cronJobService.getById(created.getId(), WORKSPACE_ID);
        assertEquals(7001L, loaded.getChannelId());
        assertNotNull(loaded.getDeliveryConfig(), "deliveryConfig must round-trip through the detail query");
        assertEquals("target-1", loaded.getDeliveryConfig().targetId());
        assertEquals("user-1", loaded.getDeliveryConfig().userId());
    }

    @Test
    @DisplayName("update rewrites channelId and deliveryConfig")
    void updateRewritesDeliveryBinding() {
        CronJobDTO created = cronJobService.create(
                newDto("delivery-update", 7001L,
                        new DeliveryConfig("target-1", null, null, "user-1", Boolean.FALSE)),
                WORKSPACE_ID);

        CronJobDTO patch = newDto("delivery-update", 7002L,
                new DeliveryConfig("target-2", null, null, "user-2", Boolean.TRUE));
        cronJobService.update(created.getId(), patch, WORKSPACE_ID);

        CronJobDTO loaded = cronJobService.getById(created.getId(), WORKSPACE_ID);
        assertEquals(7002L, loaded.getChannelId(), "channel rebinding must persist");
        assertNotNull(loaded.getDeliveryConfig());
        assertEquals("target-2", loaded.getDeliveryConfig().targetId());
        assertEquals("user-2", loaded.getDeliveryConfig().userId());
        assertTrue(loaded.getDeliveryConfig().isAgentReplySuppressed(),
                "suppressAgentReply toggle must persist");
    }

    @Test
    @DisplayName("toggle preserves the delivery binding")
    void togglePreservesDeliveryBinding() {
        CronJobDTO created = cronJobService.create(
                newDto("delivery-toggle", 7001L,
                        new DeliveryConfig("target-1", null, null, "user-1", Boolean.TRUE)),
                WORKSPACE_ID);

        cronJobService.toggle(created.getId(), true, WORKSPACE_ID);
        cronJobService.toggle(created.getId(), false, WORKSPACE_ID);

        CronJobDTO loaded = cronJobService.getById(created.getId(), WORKSPACE_ID);
        assertEquals(7001L, loaded.getChannelId());
        assertNotNull(loaded.getDeliveryConfig(),
                "enable/disable must not wipe the delivery binding");
        assertEquals("target-1", loaded.getDeliveryConfig().targetId());
        assertTrue(loaded.getDeliveryConfig().isAgentReplySuppressed());
    }

    @Test
    @DisplayName("update can clear the delivery binding")
    void updateClearsDeliveryBinding() {
        CronJobDTO created = cronJobService.create(
                newDto("delivery-clear", 7001L,
                        new DeliveryConfig("target-1", null, null, "user-1", Boolean.FALSE)),
                WORKSPACE_ID);

        CronJobDTO patch = newDto("delivery-clear", null, null);
        cronJobService.update(created.getId(), patch, WORKSPACE_ID);

        CronJobDTO loaded = cronJobService.getById(created.getId(), WORKSPACE_ID);
        assertNull(loaded.getChannelId(), "unbinding a channel must persist");
        assertNull(loaded.getDeliveryConfig(), "clearing the delivery target must persist");
    }
}
