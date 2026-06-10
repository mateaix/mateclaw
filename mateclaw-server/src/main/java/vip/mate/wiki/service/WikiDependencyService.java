package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageDependencyEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageDependencyMapper;
import vip.mate.wiki.repository.WikiPageMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains the experience→fact dependency graph and propagates staleness when
 * a fact page changes.
 *
 * <p>Dependencies are stored by page id. An edge is accepted only when the
 * target is a {@code fact}-layer page in the same KB (cross-KB and
 * experience→experience edges are rejected). When a fact page changes, every
 * page depending on it is marked stale via a single batch update keyed on the
 * reverse index, rather than per-row in the ingest transaction.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiDependencyService {

    private final WikiPageDependencyMapper dependencyMapper;
    private final WikiPageMapper pageMapper;
    private final WikiPageService pageService;
    private final ObjectMapper objectMapper;

    public WikiDependencyService(WikiPageDependencyMapper dependencyMapper, WikiPageMapper pageMapper,
                                 WikiPageService pageService, ObjectMapper objectMapper) {
        this.dependencyMapper = dependencyMapper;
        this.pageMapper = pageMapper;
        this.pageService = pageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Replace an experience page's fact dependencies. Rejected targets (missing,
     * cross-KB, or non-fact) are skipped and returned so the caller can record a
     * warning. The page's {@code depends_on_json} snapshot is refreshed too.
     *
     * @return the list of rejected target ids with a reason
     */
    public List<String> setDependencies(Long kbId, Long pageId, List<Long> dependsOnPageIds) {
        List<String> rejected = new ArrayList<>();
        Set<Long> accepted = new LinkedHashSet<>();
        if (dependsOnPageIds != null) {
            for (Long target : dependsOnPageIds) {
                if (target == null || target.equals(pageId)) {
                    continue;
                }
                WikiPageEntity targetPage = pageMapper.selectById(target);
                if (targetPage == null || !kbId.equals(targetPage.getKbId())) {
                    rejected.add(target + ": not found in this KB");
                    continue;
                }
                if (targetPage.getArchived() != null && targetPage.getArchived() == 1) {
                    rejected.add(target + ": archived");
                    continue;
                }
                if (!isFactLayer(targetPage)) {
                    rejected.add(target + ": dependency target is not a fact-layer page");
                    continue;
                }
                accepted.add(target);
            }
        }

        // Soft-delete existing edges for this page, then insert the accepted set.
        dependencyMapper.delete(new LambdaQueryWrapper<WikiPageDependencyEntity>()
                .eq(WikiPageDependencyEntity::getPageId, pageId));
        for (Long target : accepted) {
            WikiPageDependencyEntity edge = new WikiPageDependencyEntity();
            edge.setKbId(kbId);
            edge.setPageId(pageId);
            edge.setDependsOnPageId(target);
            edge.setDependencyType("fact");
            edge.setCreateTime(LocalDateTime.now());
            edge.setUpdateTime(LocalDateTime.now());
            dependencyMapper.insert(edge);
        }
        try {
            String json = objectMapper.writeValueAsString(accepted);
            pageService.setLayerAndDependencies(pageId, "experience", json);
        } catch (Exception e) {
            log.warn("[WikiDep] failed to write depends_on_json for page {}: {}", pageId, e.getMessage());
        }
        return rejected;
    }

    /**
     * Mark every page depending on {@code factPageId} as stale. Returns the
     * number of pages marked. Idempotent — re-running on already-stale pages is
     * harmless.
     */
    public int markDependentsStale(Long kbId, Long factPageId, String reason) {
        List<WikiPageDependencyEntity> edges = dependencyMapper.selectList(
                new LambdaQueryWrapper<WikiPageDependencyEntity>()
                        .eq(WikiPageDependencyEntity::getKbId, kbId)
                        .eq(WikiPageDependencyEntity::getDependsOnPageId, factPageId));
        if (edges.isEmpty()) {
            return 0;
        }
        Set<Long> dependentIds = new LinkedHashSet<>();
        for (WikiPageDependencyEntity edge : edges) {
            dependentIds.add(edge.getPageId());
        }
        String reasonJson = buildReasonJson(factPageId, reason);
        int marked = pageService.markStale(dependentIds, reasonJson);
        log.info("[WikiDep] fact page {} changed -> marked {} dependent page(s) stale", factPageId, marked);
        return marked;
    }

    private boolean isFactLayer(WikiPageEntity page) {
        String layer = page.getKnowledgeLayer();
        // Unspecified layer is treated as fact (RFC default), so legacy pages
        // remain valid dependency targets.
        return layer == null || layer.isBlank() || "fact".equalsIgnoreCase(layer.trim());
    }

    private String buildReasonJson(Long factPageId, String reason) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "factPageId", String.valueOf(factPageId),
                    "reason", reason == null ? "fact page updated" : reason));
        } catch (Exception e) {
            return "{\"factPageId\":\"" + factPageId + "\"}";
        }
    }
}
