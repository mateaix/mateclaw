package vip.mate.wiki.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPipelineRunEntity;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates the pipeline run dedup invariant against H2: two runs sharing the
 * same (definition, trigger envelope) collide on the unique key, so duplicate
 * triggers cannot spawn parallel runs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiPipelineRunMapperE2ETest {

    @Autowired
    private WikiPipelineRunMapper mapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private WikiPipelineRunEntity run(long defId, String bucket) {
        WikiPipelineRunEntity r = new WikiPipelineRunEntity();
        r.setDefinitionId(defId);
        r.setKbId(1L);
        r.setStatus("pending");
        r.setTriggerType("page_type_count");
        r.setTriggerSubject("episode");
        r.setTriggerBucket(bucket);
        r.setCreateTime(LocalDateTime.now());
        return r;
    }

    @Test
    void duplicateTriggerEnvelope_isRejected() {
        long defId = SEQ.incrementAndGet();
        WikiPipelineRunEntity first = run(defId, "20");
        mapper.insert(first);
        assertNotNull(first.getId());

        assertThrows(Exception.class, () -> mapper.insert(run(defId, "20")));
    }

    @Test
    void differentBucket_isAllowed() {
        long defId = SEQ.incrementAndGet();
        mapper.insert(run(defId, "20"));
        mapper.insert(run(defId, "40")); // next threshold bucket — distinct run
    }
}
