package vip.mate.wiki.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPageTypeProfileEntity;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the DB-level invariants of the pageType profile table against H2:
 * the V134 generated-column UNIQUE permits at most one enabled profile per KB,
 * while disabled rows coexist freely.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiPageTypeProfileMapperE2ETest {

    @Autowired
    private WikiPageTypeProfileMapper mapper;

    // The test H2 is a persistent file DB shared across runs, so each test
    // uses fresh kb ids to stay isolated from earlier runs' rows.
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private long uniqueKb() {
        return SEQ.incrementAndGet();
    }

    private WikiPageTypeProfileEntity profile(long kbId, String name, int enabled) {
        WikiPageTypeProfileEntity p = new WikiPageTypeProfileEntity();
        p.setKbId(kbId);
        p.setName(name);
        p.setVersion(1);
        p.setConfigJson("{\"version\":1,\"pageTypes\":{}}");
        p.setEnabled(enabled);
        p.setCreateTime(LocalDateTime.now());
        p.setUpdateTime(LocalDateTime.now());
        return p;
    }

    @Test
    void insertsAndReadsBack() {
        long kb = uniqueKb();
        WikiPageTypeProfileEntity p = profile(kb, "default", 1);
        mapper.insert(p);
        assertNotNull(p.getId());
        WikiPageTypeProfileEntity loaded = mapper.selectById(p.getId());
        assertEquals("default", loaded.getName());
        assertEquals(kb, loaded.getKbId());
    }

    @Test
    void secondEnabledProfileForSameKb_isRejected() {
        long kb = uniqueKb();
        mapper.insert(profile(kb, "default", 1));
        // A different name but also enabled for the same KB must violate the
        // generated-column UNIQUE (one enabled profile per KB).
        assertThrows(Exception.class, () -> mapper.insert(profile(kb, "regulation", 1)));
    }

    @Test
    void multipleDisabledProfilesForSameKb_coexist() {
        long kb = uniqueKb();
        mapper.insert(profile(kb, "default", 1));
        // enabled=0 rows yield NULL in the generated column and are exempt from
        // the unique check, so several may coexist.
        mapper.insert(profile(kb, "draft-a", 0));
        mapper.insert(profile(kb, "draft-b", 0));
        long count = mapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<WikiPageTypeProfileEntity>lambdaQuery()
                        .eq(WikiPageTypeProfileEntity::getKbId, kb));
        assertEquals(3, count);
    }

    @Test
    void enabledProfilesInDifferentKbs_coexist() {
        mapper.insert(profile(uniqueKb(), "default", 1));
        mapper.insert(profile(uniqueKb(), "default", 1));
        assertTrue(true); // no exception thrown — different KBs are independent
    }
}
