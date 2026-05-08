package vip.mate.trigger.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;
import vip.mate.workflow.runtime.WorkflowRunRequest;
import vip.mate.workflow.runtime.WorkflowRunResult;
import vip.mate.workflow.runtime.WorkflowRunner;

import java.util.Map;

/**
 * Translates a fired trigger into a workflow run. Renders the trigger's
 * {@code payloadTemplate} as JSON via Pebble, parses the result into the
 * input map, and asks the runner to execute the latest revision of the
 * target workflow. Logs and swallows failures so a bad trigger never takes
 * the scheduler thread down.
 */
@Slf4j
@Component
public class TriggerDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final WorkflowGraphLoader graphLoader;
    private final WorkflowRunner runner;
    private final PebbleSubsetEvaluator pebble;
    private final ObjectMapper objectMapper;

    public TriggerDispatcher(WorkflowGraphLoader graphLoader,
                             WorkflowRunner runner,
                             PebbleSubsetEvaluator pebble,
                             ObjectMapper objectMapper) {
        this.graphLoader = graphLoader;
        this.runner = runner;
        this.pebble = pebble;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch a single fire of {@code trigger}. {@code event} is the
     * source-event context (cron tick metadata, channel message, etc.) — its
     * top-level fields are exposed to the payload template under
     * {@code event.*}. Returns {@code null} when the trigger is not
     * dispatchable (no published revision, unsupported target type) so the
     * caller can record the skip without erroring.
     */
    public WorkflowRunResult dispatch(TriggerEntity trigger, Map<String, Object> event) {
        if (!"workflow".equalsIgnoreCase(trigger.getTargetType())) {
            log.warn("Trigger {} target_type {} not supported in v0; skipping fire",
                    trigger.getId(), trigger.getTargetType());
            return null;
        }
        WorkflowGraphLoader.Loaded loaded = graphLoader.load(trigger.getTargetId());
        if (loaded.graph() == null) {
            log.info("Trigger {} dispatch skipped: no published revision for workflow {}",
                    trigger.getId(), trigger.getTargetId());
            return null;
        }

        Map<String, Object> inputs = renderInputs(trigger, event);
        WorkflowRunRequest req = new WorkflowRunRequest(
                trigger.getTargetId(),
                loaded.revisionId(),
                trigger.getWorkspaceId(),
                "trigger:" + trigger.getId(),
                inputs);
        return runner.run(loaded.graph(), req);
    }

    private Map<String, Object> renderInputs(TriggerEntity trigger, Map<String, Object> event) {
        if (trigger.getPayloadTemplate() == null || trigger.getPayloadTemplate().isBlank()) {
            return event == null ? Map.of() : event;
        }
        try {
            var compiled = pebble.parseTemplate(trigger.getPayloadTemplate());
            String rendered = pebble.evaluateAsString(compiled,
                    Map.of("event", event == null ? Map.of() : event,
                            "trigger", Map.of(
                                    "id", trigger.getId(),
                                    "name", trigger.getName() == null ? "" : trigger.getName())));
            return objectMapper.readValue(rendered, MAP_REF);
        } catch (Exception e) {
            log.warn("Trigger {} payload template render failed; falling back to raw event: {}",
                    trigger.getId(), e.getMessage());
            return event == null ? Map.of() : event;
        }
    }
}
