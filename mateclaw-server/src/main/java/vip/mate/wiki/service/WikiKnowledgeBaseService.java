package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageCitationEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.util.List;

/**
 * Wiki 知识库服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiKnowledgeBaseService {

    private final WikiKnowledgeBaseMapper kbMapper;
    private final WikiRawMaterialMapper rawMapper;
    private final WikiPageMapper pageMapper;
    private final WikiChunkMapper chunkMapper;
    private final WikiPageCitationMapper citationMapper;
    private final WikiProcessingJobMapper processingJobMapper;

    /**
     * RFC-051 PR-2: optional system-page scaffold (overview / log). Marked
     * required=false + Lazy so the KB service has no construction dependency
     * on a service that needs WikiPageService — handy for the older tests that
     * still wire this class manually.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WikiScaffoldService scaffoldService;

    /**
     * Summary returned from cascade delete — used by callers (e.g. the
     * controller) to record an audit event with affected-row counts.
     */
    public record CascadeDeleteResult(
            String kbName,
            int rawMaterialCount,
            int pageCount,
            int chunkCount,
            int citationCount,
            int processingJobCount) {
    }

    private static final String DEFAULT_CONFIG = """
            # Wiki Processing Rules

            ## Quality First
            - Create high-quality pages — prefer fewer complete pages over many shallow ones
            - Each page focuses on one concept, entity, or process
            - A page must have at least 3 sentences of substantive content
            - Target 3-5 pages per source material (not 10-15)
            - If a concept already exists in the wiki, update it instead of duplicating

            ## Format
            - Use clear Markdown headers (## and ###)
            - Include a one-paragraph summary at the top of each page
            - Use [[Page Title]] syntax for bidirectional links between pages

            ## Updates
            - Merge new information into existing pages, do not replace
            - Preserve manually edited content (last_updated_by = 'manual')
            - Mark contradictions clearly with a "Note:" annotation

            ## Language
            - Write wiki pages in the same language as the source material
            - Keep technical terms consistent across pages
            """;

    public List<WikiKnowledgeBaseEntity> listAll() {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 按工作区列出知识库
     */
    public List<WikiKnowledgeBaseEntity> listByWorkspace(Long workspaceId) {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .eq(WikiKnowledgeBaseEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 获取 Agent 可访问的知识库：Agent 专属 KB + 公共 KB（agent_id IS NULL）
     */
    public List<WikiKnowledgeBaseEntity> listByAgentId(Long agentId) {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .and(w -> w.eq(WikiKnowledgeBaseEntity::getAgentId, agentId)
                                .or().isNull(WikiKnowledgeBaseEntity::getAgentId))
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * Resolve the single knowledge base an agent's wiki tools should operate on.
     * <p>
     * Prefers a KB explicitly bound to the agent; a shared (agent-less) KB is
     * only used as a fallback when the agent has no bound KB of its own. This
     * matters because {@link #listByAgentId} also returns shared KBs, and a
     * shared KB with a more recent {@code update_time} would otherwise win the
     * {@code get(0)} pick over the agent's own KB. Within each tier the most
     * recently updated KB wins. Returns {@code null} when the agent can reach
     * no knowledge base at all.
     */
    public WikiKnowledgeBaseEntity resolvePrimaryKb(Long agentId) {
        List<WikiKnowledgeBaseEntity> kbs = listByAgentId(agentId);
        if (kbs.isEmpty()) {
            return null;
        }
        if (agentId != null) {
            for (WikiKnowledgeBaseEntity kb : kbs) {
                if (agentId.equals(kb.getAgentId())) {
                    return kb;
                }
            }
        }
        return kbs.get(0);
    }

    public WikiKnowledgeBaseEntity getById(Long id) {
        return kbMapper.selectById(id);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId) {
        return create(name, description, agentId, 1L);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setName(name);
        entity.setDescription(description);
        entity.setAgentId(agentId);
        entity.setWorkspaceId(workspaceId);
        entity.setConfigContent(DEFAULT_CONFIG);
        entity.setStatus("active");
        entity.setPageCount(0);
        entity.setRawCount(0);
        kbMapper.insert(entity);
        log.info("[Wiki] Knowledge base created: id={}, name={}, workspaceId={}", entity.getId(), name, workspaceId);
        // RFC-051 PR-2: ensure overview / log system pages exist for every new KB.
        if (scaffoldService != null) {
            scaffoldService.ensureScaffold(entity.getId());
        }
        return entity;
    }

    @Transactional
    public WikiKnowledgeBaseEntity update(Long id, String name, String description, Long agentId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
        if (agentId != null) entity.setAgentId(agentId);
        kbMapper.updateById(entity);
        return entity;
    }

    /**
     * 更新 KB 绑定的 embedding 模型 ID。
     * <p>
     * 切换模型后，旧的向量维度/语义空间与新模型不一致，下次搜索/处理时会被
     * WikiEmbeddingService 自动检测为"model 不匹配"触发重嵌。
     * 这里不主动清空 embedding（让 embed_model 字段的差异自己触发重建）。
     *
     * @param embeddingModelId null 表示解绑（走系统默认）
     */
    @Transactional
    public void updateEmbeddingModelId(Long id, Long embeddingModelId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setEmbeddingModelId(embeddingModelId);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateConfig(Long id, String configContent) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setConfigContent(configContent);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateCounts(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        // counts will be updated by callers via specific methods
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateStatus(Long kbId, String status) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setStatus(status);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void incrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(entity.getRawCount() + 1);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void setPageCount(Long kbId, int count) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setPageCount(count);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateSourceDirectory(Long id, String path) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setSourceDirectory(path);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void decrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(Math.max(0, entity.getRawCount() - 1));
        kbMapper.updateById(entity);
    }

    /**
     * 更新知识库的 workspace 归属
     */
    public void updateWorkspaceId(Long kbId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity != null) {
            entity.setWorkspaceId(workspaceId);
            kbMapper.updateById(entity);
        }
    }

    /**
     * Cascade-delete a knowledge base and all data that belongs to it.
     * <p>
     * Single transaction: removes page citations (looked up via page IDs since
     * the citation table has no {@code kb_id} column), then chunks, pages, raw
     * materials, and processing jobs by {@code kb_id}, and finally the KB row
     * itself. Returns a summary so callers can record audit metadata.
     */
    @Transactional
    public CascadeDeleteResult delete(Long id) {
        WikiKnowledgeBaseEntity kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }

        List<Long> pageIds = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getId)
                        .eq(WikiPageEntity::getKbId, id))
                .stream()
                .map(WikiPageEntity::getId)
                .toList();

        int citationCount = pageIds.isEmpty() ? 0 : citationMapper.delete(
                new LambdaQueryWrapper<WikiPageCitationEntity>()
                        .in(WikiPageCitationEntity::getPageId, pageIds));

        int pageCount = pageMapper.delete(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, id));

        int chunkCount = chunkMapper.delete(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, id));

        int rawCount = rawMapper.delete(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, id));

        int jobCount = processingJobMapper.delete(
                new LambdaQueryWrapper<WikiProcessingJobEntity>()
                        .eq(WikiProcessingJobEntity::getKbId, id));

        kbMapper.deleteById(id);

        log.info("[Wiki] Knowledge base cascade-deleted: id={}, name={}, raw={}, page={}, chunk={}, citation={}, job={}",
                id, kb.getName(), rawCount, pageCount, chunkCount, citationCount, jobCount);

        return new CascadeDeleteResult(kb.getName(), rawCount, pageCount, chunkCount, citationCount, jobCount);
    }
}
