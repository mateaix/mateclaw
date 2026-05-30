package vip.mate.wiki.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import vip.mate.wiki.model.WikiPageDependencyEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates the page dependency table against H2: reverse lookup by
 * depends_on_page_id finds dependents, and the unique key prevents duplicate
 * edges.
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
class WikiPageDependencyMapperE2ETest {

    @Autowired
    private WikiPageDependencyMapper mapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private WikiPageDependencyEntity edge(long kb, long page, long dependsOn) {
        WikiPageDependencyEntity e = new WikiPageDependencyEntity();
        e.setKbId(kb);
        e.setPageId(page);
        e.setDependsOnPageId(dependsOn);
        e.setDependencyType("fact");
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        return e;
    }

    @Test
    void reverseLookupFindsDependents() {
        long kb = SEQ.incrementAndGet();
        long fact = SEQ.incrementAndGet();
        long expA = SEQ.incrementAndGet();
        long expB = SEQ.incrementAndGet();
        mapper.insert(edge(kb, expA, fact));
        mapper.insert(edge(kb, expB, fact));

        List<WikiPageDependencyEntity> dependents = mapper.selectList(
                Wrappers.<WikiPageDependencyEntity>lambdaQuery()
                        .eq(WikiPageDependencyEntity::getKbId, kb)
                        .eq(WikiPageDependencyEntity::getDependsOnPageId, fact));
        assertEquals(2, dependents.size());
    }

    @Test
    void duplicateEdgeRejected() {
        long kb = SEQ.incrementAndGet();
        long page = SEQ.incrementAndGet();
        long fact = SEQ.incrementAndGet();
        mapper.insert(edge(kb, page, fact));
        assertThrows(Exception.class, () -> mapper.insert(edge(kb, page, fact)));
    }
}
