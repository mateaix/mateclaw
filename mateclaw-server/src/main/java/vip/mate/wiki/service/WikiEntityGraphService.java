package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.dto.WikiEntityGraphView;
import vip.mate.wiki.dto.WikiEntityView;
import vip.mate.wiki.model.WikiEntityEntity;
import vip.mate.wiki.model.WikiEntityMentionEntity;
import vip.mate.wiki.model.WikiEntityRelationEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiEntityMapper;
import vip.mate.wiki.repository.WikiEntityMentionMapper;
import vip.mate.wiki.repository.WikiEntityRelationMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-side queries over the entity-level knowledge graph: entity listing and
 * single-entity ego-graph assembly for the wiki graph view.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiEntityGraphService {

    private final WikiEntityMapper entityMapper;
    private final WikiEntityMentionMapper mentionMapper;
    private final WikiEntityRelationMapper relationMapper;
    private final WikiPageService pageService;
    private final ObjectMapper objectMapper;

    /** List entities in a KB, optionally filtered by type, ranked by salience. */
    public List<WikiEntityView> listEntities(Long kbId, String type, int limit) {
        LambdaQueryWrapper<WikiEntityEntity> q = new LambdaQueryWrapper<WikiEntityEntity>()
                .eq(WikiEntityEntity::getKbId, kbId)
                .orderByDesc(WikiEntityEntity::getSalience)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        if (type != null && !type.isBlank()) {
            q.eq(WikiEntityEntity::getType, type.trim().toLowerCase());
        }
        List<WikiEntityView> out = new ArrayList<>();
        for (WikiEntityEntity e : entityMapper.selectList(q)) {
            out.add(toView(e));
        }
        return out;
    }

    /**
     * Assemble the whole-KB entity graph: the top entities by salience plus the
     * relation edges that connect any two of them.
     */
    public WikiEntityGraphView graph(Long kbId, int limit) {
        WikiEntityGraphView view = new WikiEntityGraphView();
        List<WikiEntityView> nodes = listEntities(kbId, null, limit);
        view.setNodes(nodes);
        if (nodes.isEmpty()) {
            return view;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiEntityView n : nodes) {
            ids.add(n.getId());
        }
        List<WikiEntityRelationEntity> rels = relationMapper.selectList(
                new LambdaQueryWrapper<WikiEntityRelationEntity>()
                        .eq(WikiEntityRelationEntity::getKbId, kbId)
                        .last("LIMIT " + Math.max(1, Math.min(limit * 5, 2000))));
        for (WikiEntityRelationEntity r : rels) {
            if (!ids.contains(r.getSubjectEntityId()) || !ids.contains(r.getObjectEntityId())) {
                continue;
            }
            WikiEntityGraphView.Edge edge = new WikiEntityGraphView.Edge();
            edge.setId(r.getId());
            edge.setSubjectEntityId(r.getSubjectEntityId());
            edge.setPredicate(r.getPredicate());
            edge.setObjectEntityId(r.getObjectEntityId());
            edge.setEvidence(r.getEvidence());
            edge.setConfidence(r.getConfidence());
            view.getEdges().add(edge);
        }
        return view;
    }

    /** Assemble the ego-graph around one entity. */
    public WikiEntityGraphView ego(Long kbId, Long entityId, int limit) {
        WikiEntityGraphView view = new WikiEntityGraphView();
        WikiEntityEntity center = entityMapper.selectById(entityId);
        if (center == null || !center.getKbId().equals(kbId)) {
            return view;
        }
        view.setCenter(toView(center));

        int edgeLimit = Math.max(1, Math.min(limit, 200));
        List<WikiEntityRelationEntity> edges = relationMapper.selectList(
                new LambdaQueryWrapper<WikiEntityRelationEntity>()
                        .eq(WikiEntityRelationEntity::getKbId, kbId)
                        .and(w -> w.eq(WikiEntityRelationEntity::getSubjectEntityId, entityId)
                                .or().eq(WikiEntityRelationEntity::getObjectEntityId, entityId))
                        .last("LIMIT " + edgeLimit));

        Set<Long> neighborIds = new LinkedHashSet<>();
        for (WikiEntityRelationEntity r : edges) {
            WikiEntityGraphView.Edge edge = new WikiEntityGraphView.Edge();
            edge.setId(r.getId());
            edge.setSubjectEntityId(r.getSubjectEntityId());
            edge.setPredicate(r.getPredicate());
            edge.setObjectEntityId(r.getObjectEntityId());
            edge.setEvidence(r.getEvidence());
            edge.setConfidence(r.getConfidence());
            view.getEdges().add(edge);
            neighborIds.add(r.getSubjectEntityId());
            neighborIds.add(r.getObjectEntityId());
        }
        neighborIds.remove(entityId);
        if (!neighborIds.isEmpty()) {
            for (WikiEntityEntity n : entityMapper.selectBatchIds(neighborIds)) {
                view.getNodes().add(toView(n));
            }
        }

        // Pages that mention the center entity — the bridge to the page layer.
        List<WikiEntityMentionEntity> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<WikiEntityMentionEntity>()
                        .eq(WikiEntityMentionEntity::getEntityId, entityId)
                        .isNotNull(WikiEntityMentionEntity::getPageId)
                        .last("LIMIT 200"));
        Set<Long> pageIds = new LinkedHashSet<>();
        for (WikiEntityMentionEntity m : mentions) {
            pageIds.add(m.getPageId());
        }
        for (Long pageId : pageIds) {
            WikiPageEntity page = pageService.getById(pageId);
            if (page == null) {
                continue;
            }
            WikiEntityGraphView.PageRef ref = new WikiEntityGraphView.PageRef();
            ref.setPageId(page.getId());
            ref.setSlug(page.getSlug());
            ref.setTitle(page.getTitle());
            view.getPages().add(ref);
        }
        return view;
    }

    private WikiEntityView toView(WikiEntityEntity e) {
        WikiEntityView v = new WikiEntityView();
        v.setId(e.getId());
        v.setKbId(e.getKbId());
        v.setCanonicalName(e.getCanonicalName());
        v.setType(e.getType());
        v.setDescription(e.getDescription());
        v.setSalience(e.getSalience());
        v.setMentionCount(e.getMentionCount());
        v.setAliases(parseAliases(e.getAliasesJson()));
        return v;
    }

    private List<String> parseAliases(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
