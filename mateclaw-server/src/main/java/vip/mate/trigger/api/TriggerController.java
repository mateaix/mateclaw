package vip.mate.trigger.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.service.TriggerService;

import java.util.List;
import java.util.Map;

/**
 * REST surface for cron / event triggers. The generic event ingest endpoint
 * exists so external systems (n8n, GitHub webhooks, ad-hoc curl) can post
 * events without going through a dedicated channel adapter — useful for
 * smoke-testing a trigger before the channel integration lands.
 */
@Tag(name = "触发器管理")
@RestController
@RequestMapping("/api/v1/triggers")
@RequiredArgsConstructor
public class TriggerController {

    private final TriggerService triggerService;
    private final TriggerEventIngestService ingestService;

    @Operation(summary = "List triggers in a workspace.")
    @GetMapping
    public R<List<TriggerEntity>> list(@RequestParam("workspaceId") long workspaceId) {
        return R.ok(triggerService.listByWorkspace(workspaceId));
    }

    @Operation(summary = "Get a trigger by id.")
    @GetMapping("/{id}")
    public R<TriggerEntity> get(@PathVariable long id) {
        TriggerEntity row = triggerService.get(id);
        if (row == null) return R.fail("trigger not found: " + id);
        return R.ok(row);
    }

    @Operation(summary = "Create a trigger; if enabled, registers it with the scheduler.")
    @PostMapping
    public R<TriggerEntity> create(@RequestBody TriggerEntity trigger) {
        return R.ok(triggerService.create(trigger));
    }

    @Operation(summary = "Update a trigger; pattern_version bumps when the cron expression changes.")
    @PutMapping("/{id}")
    public R<TriggerEntity> update(@PathVariable long id, @RequestBody TriggerEntity trigger) {
        trigger.setId(id);
        return R.ok(triggerService.update(trigger));
    }

    @Operation(summary = "Delete a trigger and unregister its schedule.")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        triggerService.delete(id);
        return R.ok();
    }

    /**
     * Ingest one event envelope through the dedup / rate-limit / bot-self
     * pipeline. The endpoint is open to operators; production deployments
     * SHOULD gate it behind the workspace interceptor / a service token
     * before exposing it externally.
     */
    @Operation(summary = "Ingest one event envelope; returns per-trigger fire / drop summary.")
    @PostMapping("/events")
    public R<List<TriggerEventIngestService.IngestResult>> ingestEvent(
            @RequestBody EventIngestRequest body) {
        TriggerEventEnvelope env = new TriggerEventEnvelope(
                body.workspaceId(),
                body.patternType(),
                body.eventId(),
                body.senderId(),
                body.data() == null ? Map.of() : body.data());
        return R.ok(ingestService.ingest(env));
    }

    public record EventIngestRequest(
            long workspaceId,
            String patternType,
            String eventId,
            String senderId,
            Map<String, Object> data) {}
}
