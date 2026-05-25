package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.audit.service.AuditEventService;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * Covers the run notifier: every completed sweep records a durable audit row
 * and publishes a {@link SkillCuratorRunCompletedEvent}.
 */
@ExtendWith(MockitoExtension.class)
class CuratorRunNotifierTest {

    @Mock
    private AuditEventService auditEventService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CuratorRunNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new CuratorRunNotifier(auditEventService, eventPublisher, new ObjectMapper());
    }

    @Test
    void onRunCompleteRecordsAuditAndPublishesEvent() {
        SkillCuratorReport report = SkillCuratorReport.builder()
                .runAt(LocalDateTime.now())
                .dryRun(false)
                .config(30, 90, "AGENT_CREATED")
                .plannedCounts(2, 1, 0)
                .appliedCounts(2, 1, 0)
                .build();

        notifier.onRunComplete(report);

        verify(auditEventService).record(eq("CURATOR_RUN"), eq("SKILL"),
                eq(report.getRunId()), isNull(), anyString());
        verify(eventPublisher).publishEvent(any(SkillCuratorRunCompletedEvent.class));
    }
}
