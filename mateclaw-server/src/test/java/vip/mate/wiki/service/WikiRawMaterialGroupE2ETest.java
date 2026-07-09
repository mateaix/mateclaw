package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Against a real H2 database: {@link WikiRawMaterialService#updateGroup} must
 * be able to null out groupId, not just set it. MyBatis-Plus's default
 * NOT_NULL update strategy silently skips null fields on updateById, which is
 * why the service uses LambdaUpdateWrapper#set explicitly — this test proves
 * that choice actually round-trips through a real update statement rather
 * than a mocked mapper.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiRawMaterialGroupE2ETest {

    @Autowired
    private WikiRawMaterialService rawService;
    @Autowired
    private WikiRawMaterialMapper rawMapper;
    @Autowired
    private WikiKnowledgeBaseMapper kbMapper;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void updateGroup_setsThenClearsGroupId() {
        long kbId = SEQ.incrementAndGet();
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(kbId);
        kb.setName("KB-Group-" + kbId);
        kb.setStatus("active");
        kb.setWorkspaceId(1L);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setDeleted(0);
        kbMapper.insert(kb);

        long rawId = SEQ.incrementAndGet();
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(rawId);
        raw.setKbId(kbId);
        raw.setTitle("raw-" + rawId);
        raw.setSourceType("text");
        raw.setProcessingStatus("completed");
        raw.setCreateTime(LocalDateTime.now());
        raw.setUpdateTime(LocalDateTime.now());
        raw.setDeleted(0);
        rawMapper.insert(raw);

        rawService.updateGroup(rawId, 5L);
        assertEquals(5L, rawMapper.selectById(rawId).getGroupId(),
                "groupId should be set to the target group");

        rawService.updateGroup(rawId, null);
        assertNull(rawMapper.selectById(rawId).getGroupId(),
                "groupId must actually be cleared, not skipped by the NOT_NULL update strategy");
    }
}
