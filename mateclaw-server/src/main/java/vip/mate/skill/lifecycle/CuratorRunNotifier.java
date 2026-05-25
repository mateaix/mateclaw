package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.audit.service.AuditEventService;

import java.util.Map;

/**
 * Surfaces a completed lifecycle sweep through two decoupled channels: a
 * durable {@code mate_audit_event} row, and a Spring application event that
 * a notification subsystem may listen for. Neither channel couples the
 * curator to any subsystem that may not be present.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CuratorRunNotifier {

    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public void onRunComplete(SkillCuratorReport report) {
        // (1) Durable audit trail — always recorded.
        try {
            String detail = objectMapper.writeValueAsString(Map.of(
                    "marked_stale", report.markedStale(),
                    "archived", report.archived(),
                    "reactivated", report.reactivated(),
                    "dryRun", report.isDryRun(),
                    "reportPath", String.valueOf(report.getPath())));
            auditEventService.record("CURATOR_RUN", "SKILL", report.getRunId(), null, detail);
        } catch (Exception e) {
            log.debug("Failed to record curator run audit event: {}", e.getMessage());
        }

        // (2) Application event — no listener is required; if none exists
        // the event is simply discarded.
        eventPublisher.publishEvent(new SkillCuratorRunCompletedEvent(
                report.getRunId(), report.markedStale(), report.archived(),
                report.reactivated(), report.isDryRun(), report.getPath(), report.getRunAt()));
    }
}
