package vip.mate.wiki.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.model.WikiPipelineStepRunEntity;
import vip.mate.wiki.repository.WikiPipelineStepRunMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration test for {@link WikiPipelineService} against H2 with stub
 * executors: success path records succeeded run + steps, a failing step fails
 * the run and stops, and a duplicate trigger envelope is skipped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WikiPipelineServiceE2ETest {

    /** Stub executors: 'echo' returns a marker; 'boom' always throws. */
    @TestConfiguration
    static class StubExecutors {
        @Bean
        WikiStepExecutor echoExecutor() {
            return new WikiStepExecutor() {
                public String type() { return "echo"; }
                public String execute(WikiStepContext c) {
                    return "echo:" + c.stepId() + ":" + (c.previousOutput() == null ? "" : c.previousOutput());
                }
            };
        }
        @Bean
        WikiStepExecutor boomExecutor() {
            return new WikiStepExecutor() {
                public String type() { return "boom"; }
                public String execute(WikiStepContext c) throws Exception { throw new RuntimeException("kaboom"); }
            };
        }
    }

    @Autowired
    private WikiPipelineService pipelineService;
    @Autowired
    private WikiPipelineStepRunMapper stepRunMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private WikiPipelineDefinitionEntity def(String stepsJson) {
        WikiPipelineDefinitionEntity d = new WikiPipelineDefinitionEntity();
        d.setId(SEQ.incrementAndGet());
        d.setKbId(1L);
        d.setName("p" + d.getId());
        d.setOwnerAgentId(42L);
        d.setTriggerType("page_type_count");
        d.setStepsJson(stepsJson);
        d.setEnabled(1);
        return d;
    }

    @Test
    void successPath_runsAllSteps_chainingOutput() {
        WikiPipelineDefinitionEntity d = def(
                "[{\"id\":\"a\",\"executor\":\"echo\"},{\"id\":\"b\",\"executor\":\"echo\"}]");
        WikiPipelineService.RunOutcome outcome = pipelineService.execute(d, "episode", "20", null);

        assertFalse(outcome.duplicate());
        assertNotNull(outcome.run());
        assertEquals("succeeded", outcome.run().getStatus());
        // step b sees step a's output (chaining)
        assertEquals("echo:b:echo:a:", outcome.run().getOutputJson());

        List<WikiPipelineStepRunEntity> steps = stepRunMapper.selectList(
                Wrappers.<WikiPipelineStepRunEntity>lambdaQuery()
                        .eq(WikiPipelineStepRunEntity::getRunId, outcome.run().getId()));
        assertEquals(2, steps.size());
        assertTrue(steps.stream().allMatch(s -> s.getStatus().equals("succeeded")));
    }

    @Test
    void failingStep_failsRun_andStops() {
        WikiPipelineDefinitionEntity d = def(
                "[{\"id\":\"a\",\"executor\":\"echo\"},{\"id\":\"b\",\"executor\":\"boom\"},{\"id\":\"c\",\"executor\":\"echo\"}]");
        WikiPipelineService.RunOutcome outcome = pipelineService.execute(d, "episode", "20", null);

        assertEquals("failed", outcome.run().getStatus());
        assertTrue(outcome.run().getErrorMessage().contains("kaboom"));
        // step c must NOT have run (pipeline stopped at b)
        List<WikiPipelineStepRunEntity> steps = stepRunMapper.selectList(
                Wrappers.<WikiPipelineStepRunEntity>lambdaQuery()
                        .eq(WikiPipelineStepRunEntity::getRunId, outcome.run().getId()));
        assertEquals(2, steps.size());
    }

    @Test
    void duplicateTrigger_isSkipped() {
        WikiPipelineDefinitionEntity d = def("[{\"id\":\"a\",\"executor\":\"echo\"}]");
        WikiPipelineService.RunOutcome first = pipelineService.execute(d, "episode", "20", null);
        assertFalse(first.duplicate());

        WikiPipelineService.RunOutcome second = pipelineService.execute(d, "episode", "20", null);
        assertTrue(second.duplicate());
        assertNull(second.run());
    }

    @Test
    void unknownExecutor_failsRun() {
        WikiPipelineDefinitionEntity d = def("[{\"id\":\"a\",\"executor\":\"nope\"}]");
        WikiPipelineService.RunOutcome outcome = pipelineService.execute(d, "episode", "20", null);
        assertEquals("failed", outcome.run().getStatus());
        assertTrue(outcome.run().getErrorMessage().contains("No executor"));
    }
}
