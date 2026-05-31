package vip.mate.wiki.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.model.WikiPipelineRunEntity;
import vip.mate.wiki.model.WikiPipelineStepRunEntity;
import vip.mate.wiki.repository.WikiPipelineRunMapper;
import vip.mate.wiki.repository.WikiPipelineStepRunMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs a wiki pipeline definition: creates a dedup-guarded run, executes each
 * step through the matching {@link WikiStepExecutor} under the definition's
 * owner agent, and records run / step status.
 *
 * <p>Run creation is idempotent: a duplicate trigger envelope collides on the
 * run table's unique key and is skipped, so concurrent instances cannot spawn
 * parallel runs for the same trigger.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiPipelineService {

    private final WikiPipelineRunMapper runMapper;
    private final WikiPipelineStepRunMapper stepRunMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, WikiStepExecutor> executors;

    public WikiPipelineService(WikiPipelineRunMapper runMapper,
                               WikiPipelineStepRunMapper stepRunMapper,
                               ObjectMapper objectMapper,
                               List<WikiStepExecutor> executorBeans) {
        this.runMapper = runMapper;
        this.stepRunMapper = stepRunMapper;
        this.objectMapper = objectMapper;
        this.executors = new java.util.HashMap<>();
        for (WikiStepExecutor e : executorBeans) {
            this.executors.put(e.type(), e);
        }
    }

    /** Outcome of an attempted run. {@code run} is null when skipped as a duplicate. */
    public record RunOutcome(WikiPipelineRunEntity run, boolean duplicate) {}

    /**
     * Execute a definition for one trigger. Returns {@code duplicate=true} with
     * a null run when the trigger envelope was already handled.
     */
    public RunOutcome execute(WikiPipelineDefinitionEntity def, String triggerSubject,
                              String triggerBucket, String inputJson) {
        if (def.getEnabled() != null && def.getEnabled() == 0) {
            return new RunOutcome(null, false);
        }
        if (def.getOwnerAgentId() == null) {
            throw new IllegalStateException("Pipeline definition " + def.getId() + " has no owner agent");
        }

        WikiPipelineRunEntity run = new WikiPipelineRunEntity();
        run.setDefinitionId(def.getId());
        run.setKbId(def.getKbId());
        run.setStatus("running");
        run.setTriggerType(def.getTriggerType());
        run.setTriggerSubject(triggerSubject);
        run.setTriggerBucket(triggerBucket);
        run.setInputJson(inputJson);
        run.setStartedAt(LocalDateTime.now());
        run.setCreateTime(LocalDateTime.now());
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException dup) {
            // Another instance / earlier trigger already created this run.
            log.info("[WikiPipeline] duplicate trigger for def={} subject={} bucket={} — skipped",
                    def.getId(), triggerSubject, triggerBucket);
            return new RunOutcome(null, true);
        }

        List<Map<String, Object>> steps = parseSteps(def.getStepsJson());
        String previousOutput = null;
        try {
            for (Map<String, Object> step : steps) {
                previousOutput = runStep(def, run.getId(), step, previousOutput);
            }
            finishRun(run, "succeeded", previousOutput, null);
        } catch (StepFailure f) {
            finishRun(run, "failed", previousOutput, f.getMessage());
        }
        return new RunOutcome(run, false);
    }

    private String runStep(WikiPipelineDefinitionEntity def, Long runId, Map<String, Object> step,
                           String previousOutput) throws StepFailure {
        String stepId = String.valueOf(step.getOrDefault("id", "step"));
        String executorType = String.valueOf(step.getOrDefault("executor", ""));

        WikiPipelineStepRunEntity stepRun = new WikiPipelineStepRunEntity();
        stepRun.setRunId(runId);
        stepRun.setStepId(stepId);
        stepRun.setExecutor(executorType);
        stepRun.setStatus("running");
        stepRun.setStartedAt(LocalDateTime.now());
        stepRun.setCreateTime(LocalDateTime.now());
        stepRunMapper.insert(stepRun);

        WikiStepExecutor executor = executors.get(executorType);
        if (executor == null) {
            String msg = "No executor registered for type '" + executorType + "'";
            failStep(stepRun, msg);
            throw new StepFailure(msg);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = step.get("config") instanceof Map
                    ? (Map<String, Object>) step.get("config") : Map.of();
            String output = executor.execute(new WikiStepContext(
                    def.getKbId(), def.getOwnerAgentId(), stepId, config, previousOutput));
            stepRun.setStatus("succeeded");
            stepRun.setOutputJson(output);
            stepRun.setFinishedAt(LocalDateTime.now());
            stepRunMapper.updateById(stepRun);
            return output;
        } catch (Exception e) {
            String msg = "Step '" + stepId + "' failed: " + e.getMessage();
            failStep(stepRun, msg);
            throw new StepFailure(msg);
        }
    }

    private void failStep(WikiPipelineStepRunEntity stepRun, String message) {
        stepRun.setStatus("failed");
        stepRun.setErrorMessage(truncate(message));
        stepRun.setFinishedAt(LocalDateTime.now());
        stepRunMapper.updateById(stepRun);
    }

    private void finishRun(WikiPipelineRunEntity run, String status, String output, String error) {
        run.setStatus(status);
        run.setOutputJson(output);
        run.setErrorMessage(truncate(error));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private List<Map<String, Object>> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stepsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[WikiPipeline] unparseable steps_json: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    /** Internal control-flow signal that a step failed and the run should stop. */
    private static final class StepFailure extends Exception {
        StepFailure(String message) {
            super(message);
        }
    }
}
