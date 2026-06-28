package vip.mate.wiki.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.dto.WikiFailureItem;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the centralized failure queries against H2: the NEEDS_ATTENTION
 * predicate must capture failed / partial / warning rows and exclude clean
 * completed and pending ones, and the list must join the KB display name.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiRawMaterialFailuresMapperE2ETest {

    @Autowired
    private WikiRawMaterialMapper rawMapper;
    @Autowired
    private WikiKnowledgeBaseMapper kbMapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private long newKb(String name) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        long id = SEQ.incrementAndGet();
        kb.setId(id);
        kb.setName(name);
        kb.setStatus("active");
        kb.setWorkspaceId(1L);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setDeleted(0);
        kbMapper.insert(kb);
        return id;
    }

    private long raw(long kbId, String status, String errorCode, String warningCode) {
        WikiRawMaterialEntity r = new WikiRawMaterialEntity();
        long id = SEQ.incrementAndGet();
        r.setId(id);
        r.setKbId(kbId);
        r.setTitle("raw-" + id);
        r.setSourceType("text");
        r.setProcessingStatus(status);
        r.setErrorCode(errorCode);
        r.setWarningCode(warningCode);
        r.setCreateTime(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        r.setDeleted(0);
        rawMapper.insert(r);
        return id;
    }

    @Test
    void needsAttentionPredicateCapturesTheRightRows() {
        long kb = newKb("KB-Failures");
        long failed = raw(kb, "failed", "AUTH_ERROR", null);
        long partial = raw(kb, "partial", null, null);
        long degraded = raw(kb, "completed", null, "EMBEDDING_FAILED");
        long clean = raw(kb, "completed", null, null);
        long pending = raw(kb, "pending", null, null);

        long countBefore = rawMapper.countFailures();
        assertTrue(countBefore >= 3, "count should include the 3 attention-needing rows");

        List<WikiFailureItem> mine = rawMapper.listFailures(500).stream()
                .filter(i -> i.kbId().equals(kb))
                .toList();

        List<Long> ids = mine.stream().map(WikiFailureItem::rawId).toList();
        assertTrue(ids.contains(failed), "failed row must surface");
        assertTrue(ids.contains(partial), "partial row must surface");
        assertTrue(ids.contains(degraded), "degraded (warning) row must surface");
        assertFalse(ids.contains(clean), "clean completed row must not surface");
        assertFalse(ids.contains(pending), "pending row must not surface");

        // Join carries the KB display name through to the projection.
        assertEquals("KB-Failures", mine.get(0).kbName());
    }
}
