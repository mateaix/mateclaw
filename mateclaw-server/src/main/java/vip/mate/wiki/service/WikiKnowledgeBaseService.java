package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
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
    private final AgentMapper agentMapper;

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
     * 获取 Agent 可访问的知识库。
     * <p>
     * Knowledge bases are workspace-shared. The agent's primary KB is stored
     * on mate_agent.primary_kb_id and does not affect visibility.
     */
    public List<WikiKnowledgeBaseEntity> listByAgentId(Long agentId) {
        AgentEntity agent = getAgentOrNull(agentId);
        if (agent == null || agent.getWorkspaceId() == null) {
            return listAll();
        }
        return listByWorkspace(agent.getWorkspaceId());
    }

    /**
     * Resolve the single knowledge base an agent's wiki tools should operate on.
     * <p>
     * Prefers mate_agent.primary_kb_id when it points to a KB in the same
     * workspace. For legacy rows that predate primary_kb_id, falls back to the
     * old mate_wiki_knowledge_base.agent_id marker only when no primary is set.
     * Otherwise the most recently updated workspace KB wins.
     */
    public WikiKnowledgeBaseEntity resolvePrimaryKb(Long agentId) {
        AgentEntity agent = getAgentOrNull(agentId);
        List<WikiKnowledgeBaseEntity> kbs = listByAgentId(agentId);
        if (kbs.isEmpty()) {
            return null;
        }
        Long primaryKbId = agent != null ? agent.getPrimaryKbId() : null;
        if (primaryKbId != null) {
            for (WikiKnowledgeBaseEntity kb : kbs) {
                if (primaryKbId.equals(kb.getId()) && sameWorkspace(agent, kb)) {
                    return kb;
                }
            }
            return kbs.get(0);
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

    private AgentEntity getAgentOrNull(Long agentId) {
        if (agentId == null || agentMapper == null) {
            return null;
        }
        return agentMapper.selectById(agentId);
    }

    private boolean sameWorkspace(AgentEntity agent, WikiKnowledgeBaseEntity kb) {
        if (agent == null || kb == null || agent.getWorkspaceId() == null) {
            return true;
        }
        return kb.getWorkspaceId() == null || agent.getWorkspaceId().equals(kb.getWorkspaceId());
    }

    /**
     * Resolve a specific knowledge base by name, restricted to the agent's
     * workspace-visible KB set. Used by wiki tools
     * that accept an optional {@code kbName} parameter so the LLM can target
     * a non-primary KB when the agent reaches more than one.
     * <p>
     * Match is exact and case-sensitive — the LLM is expected to copy the
     * name verbatim from {@code wiki_list_kbs} output. Returns {@code null}
     * when zero OR more than one KB matches the name; callers wanting to
     * distinguish the two cases (so the LLM can be told to disambiguate by
     * id) should call {@link #findAllByName} instead. The single-match
     * convenience contract here keeps the legacy call sites simple.
     */
    public WikiKnowledgeBaseEntity findByName(Long agentId, String kbName) {
        List<WikiKnowledgeBaseEntity> matches = findAllByName(agentId, kbName);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * All KBs visible to {@code agentId} whose name matches {@code kbName}
     * exactly. Returns an empty list when the name is blank or no KB matches;
     * returns >1 entries when the workspace has duplicate KB names (no DB
     * unique constraint protects against this), in which case the caller
     * MUST disambiguate (typically by surfacing a kbId-based picker to the
     * LLM) rather than silently picking the first one.
     */
    public List<WikiKnowledgeBaseEntity> findAllByName(Long agentId, String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return List.of();
        }
        List<WikiKnowledgeBaseEntity> out = new java.util.ArrayList<>();
        for (WikiKnowledgeBaseEntity kb : listByAgentId(agentId)) {
            if (kbName.equals(kb.getName())) {
                out.add(kb);
            }
        }
        return out;
    }

    /**
     * Resolve a KB by id, but ONLY when it's in the agent's visibility set —
     * a deliberate fail-closed gate so an LLM cannot pivot to an arbitrary KB
     * by guessing or scraping an id from someone else's workspace.
     */
    public WikiKnowledgeBaseEntity findVisibleById(Long agentId, Long kbId) {
        if (kbId == null) return null;
        for (WikiKnowledgeBaseEntity kb : listByAgentId(agentId)) {
            if (kbId.equals(kb.getId())) {
                return kb;
            }
        }
        return null;
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
    public WikiKnowledgeBaseEntity update(Long id, String name, String description) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
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
