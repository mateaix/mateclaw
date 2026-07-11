package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** Runs against an isolated in-memory H2 instead of the shared persistent
     * {@code ./data/mateclaw} the default profile points at, so this class no
     * longer writes test rows into the local dev database on every run. */
    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        String dbName = "wiki_e2e_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private WikiRawMaterialService rawService;
    @Autowired
    private WikiRawMaterialMapper rawMapper;
    @Autowired
    private WikiKnowledgeBaseMapper kbMapper;
    @Autowired
    private WikiSourceGroupService groupService;

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

    /**
     * C2: tombstone mechanism — after soft-deleting a group, a new group with
     * the same alias must be creatable. The tombstone rename (alias#del#id) +
     * @TableLogic keeps the soft-deleted row invisible to assertAliasAvailable,
     * and the renamed alias frees the original name in the unique index.
     * This is the only E2E proof that the full lifecycle works against a real DB.
     */
    @Test
    void delete_thenRecreateSameAlias_succeeds() {
        long kbId = SEQ.incrementAndGet();
        createKb(kbId);

        WikiSourceGroupEntity group = groupService.create(kbId, "docs", "/data/docs", null, null, true);
        assertNotNull(group.getId());

        // Soft-delete it — tombstone rename + @TableLogic soft-delete.
        groupService.delete(kbId, group.getId(), null);

        // The soft-deleted group must be invisible to getById (proves @TableLogic works).
        assertNull(groupService.getById(group.getId()),
                "soft-deleted group should be invisible to selectById");

        // Recreating with the same alias must succeed (proves tombstone freed the name).
        WikiSourceGroupEntity recreated = groupService.create(kbId, "docs", "/data/docs", null, null, true);
        assertNotNull(recreated.getId());
        assertNotEquals(group.getId(), recreated.getId(),
                "recreated group should have a new id, not reuse the soft-deleted one");
        assertEquals("docs", recreated.getAlias());
    }

    /**
     * H3: reassignGroup with null target — when a group is deleted without a
     * reassignTo target, all its raws must have their groupId cleared to null.
     * This uses LambdaUpdateWrapper#set (not updateById) to bypass the
     * NOT_NULL strategy, same as updateGroup. This E2E proves it round-trips.
     */
    @Test
    void deleteGroup_clearsGroupIdOnAllRaws() {
        long kbId = SEQ.incrementAndGet();
        createKb(kbId);

        WikiSourceGroupEntity group = groupService.create(kbId, "notes", "/data/notes", null, null, true);
        Long groupId = group.getId();

        // Create two raws belonging to this group.
        long raw1 = SEQ.incrementAndGet();
        long raw2 = SEQ.incrementAndGet();
        createRaw(raw1, kbId, groupId);
        createRaw(raw2, kbId, groupId);

        // Delete the group without reassignTo — raws should become ungrouped.
        groupService.delete(kbId, groupId, null);

        assertNull(rawMapper.selectById(raw1).getGroupId(),
                "raw1 groupId must be cleared after group deletion");
        assertNull(rawMapper.selectById(raw2).getGroupId(),
                "raw2 groupId must be cleared after group deletion");
    }

    // ---- helpers ----

    private void createKb(long kbId) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(kbId);
        kb.setName("KB-E2E-" + kbId);
        kb.setStatus("active");
        kb.setWorkspaceId(1L);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setDeleted(0);
        kbMapper.insert(kb);
    }

    private void createRaw(long rawId, long kbId, Long groupId) {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(rawId);
        raw.setKbId(kbId);
        raw.setGroupId(groupId);
        raw.setTitle("raw-" + rawId);
        raw.setSourceType("text");
        raw.setProcessingStatus("completed");
        raw.setCreateTime(LocalDateTime.now());
        raw.setUpdateTime(LocalDateTime.now());
        raw.setDeleted(0);
        rawMapper.insert(raw);
    }
}
