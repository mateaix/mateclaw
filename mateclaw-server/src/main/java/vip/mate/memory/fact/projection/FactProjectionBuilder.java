package vip.mate.memory.fact.projection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.extraction.CompositeEntityExtractor;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the fact projection from canonical sources.
 * <p>
 * Derived columns are overwritten; accumulated columns (use_count, last_used_at)
 * are preserved via select-then-update keyed on
 * (agent_id, source_ref, scope, owner_key).
 * <p>
 * Only this class may write derived columns to mate_fact (core invariant).
 * Uses MyBatis Plus CRUD (dialect-safe for both H2 and MySQL).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactProjectionBuilder {

    private final FactMapper factMapper;
    private final WorkspaceFileService workspaceFileService;
    private final CompositeEntityExtractor extractor;
    private final MemoryProperties properties;

    /**
     * Full rebuild for an agent. Extracts facts from all canonical sources,
     * upserts derived columns, and soft-deletes stale entries.
     */
    public int rebuildAll(Long agentId) {
        if (!properties.getFact().isProjectionEnabled()) {
            log.debug("[FactProjection] Projection disabled, skipping rebuildAll for agent={}", agentId);
            return 0;
        }

        List<ProjectedFact> allFacts = new ArrayList<>();

        // Extract every canonical memory row with its visibility identity. A
        // shared agent can have the same filename/key for many personal owners,
        // so filename/sourceRef alone is not a projection identity.
        List<WorkspaceFileEntity> files = workspaceFileService.listFiles(agentId);
        for (WorkspaceFileEntity file : files) {
            String filename = file.getFilename();
            if (filename == null) continue;
            boolean canonical = "MEMORY.md".equals(filename)
                    || filename.startsWith("structured/") && filename.endsWith(".md");
            if (!canonical) continue;

            String scope = normalizeScope(file.getScope());
            String ownerKey = normalizeOwner(file.getOwnerKey(), scope);
            WorkspaceFileEntity full = MemoryScope.PERSONAL.equals(scope)
                    ? workspaceFileService.getMemoryFile(agentId, filename, ownerKey)
                    : workspaceFileService.getFile(agentId, filename);
            if (full == null || full.getContent() == null || full.getContent().isBlank()) continue;
            for (ExtractedFact fact : extractor.extract(agentId, filename, full.getContent())) {
                allFacts.add(new ProjectedFact(fact, ownerKey, scope));
            }
        }

        // Upsert all extracted facts (dialect-safe)
        LocalDateTime now = LocalDateTime.now();
        List<Long> keepIds = new ArrayList<>();
        for (ProjectedFact projected : allFacts) {
            Long id = upsertDerived(agentId, projected.fact(), projected.ownerKey(), projected.scope(), now);
            if (id != null) keepIds.add(id);
        }

        // Remove stale facts by row ID. source_ref is intentionally not unique
        // across owners, so a source-ref keep set cannot express owner identity.
        if (!keepIds.isEmpty() && keepIds.size() == allFacts.size()) {
            factMapper.deleteByAgentIdAndIdNotIn(agentId, keepIds, now);
        }

        log.info("[FactProjection] rebuildAll: agent={}, facts={}", agentId, allFacts.size());
        return allFacts.size();
    }

    /**
     * Incremental rebuild for a single file change.
     */
    public int rebuildOne(Long agentId, String filename, String content) {
        return rebuildOne(agentId, filename, content, "", MemoryScope.TEAM);
    }

    /** Incremental owner-aware rebuild for one canonical memory row. */
    public int rebuildOne(Long agentId, String filename, String content, String ownerKey, String scope) {
        if (!properties.getFact().isProjectionEnabled()) return 0;

        List<ExtractedFact> facts = extractor.extract(agentId, filename, content);
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : facts) {
            String normalizedScope = normalizeScope(scope);
            upsertDerived(agentId, fact, normalizeOwner(ownerKey, normalizedScope), normalizedScope, now);
        }
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, facts={}", agentId, filename, facts.size());
        return facts.size();
    }

    /**
     * Dialect-safe upsert: select by owner-aware projection identity, then insert or update.
     * Preserves accumulated columns (use_count, last_used_at) on update.
     */
    private Long upsertDerived(Long agentId, ExtractedFact fact, String ownerKey,
                               String scope, LocalDateTime now) {
        LambdaQueryWrapper<FactEntity> identity = new LambdaQueryWrapper<FactEntity>()
                .eq(FactEntity::getAgentId, agentId)
                .eq(FactEntity::getSourceRef, fact.sourceRef())
                .eq(FactEntity::getScope, scope);
        if (MemoryScope.PERSONAL.equals(scope)) {
            identity.eq(FactEntity::getOwnerKey, ownerKey);
        } else {
            // V137 backfilled scope but historical fact rows may still have a
            // null owner, while newer shared canonical rows use the "" sentinel.
            identity.and(w -> w.isNull(FactEntity::getOwnerKey)
                    .or().eq(FactEntity::getOwnerKey, ""));
        }
        FactEntity existing = factMapper.selectOne(identity.last("LIMIT 1"));

        if (existing != null) {
            // Update derived columns only; preserve accumulated columns
            existing.setCategory(fact.category());
            existing.setSubject(fact.subject());
            existing.setPredicate(fact.predicate());
            existing.setObjectValue(fact.objectValue());
            existing.setConfidence(fact.confidence());
            existing.setExtractedBy(fact.extractedBy());
            existing.setOwnerKey(ownerKey);
            existing.setScope(scope);
            // Trust derived from canonical feedback metadata, then time-decayed
            double baseTrust = fact.trust();
            existing.setTrust(applyTimeDecay(baseTrust, existing.getUpdateTime(), now));
            existing.setUpdateTime(now);
            existing.setDeleted(0); // un-delete if previously soft-deleted
            factMapper.updateById(existing);
            return existing.getId();
        } else {
            FactEntity entity = new FactEntity();
            entity.setAgentId(agentId);
            entity.setSourceRef(fact.sourceRef());
            entity.setCategory(fact.category());
            entity.setSubject(fact.subject());
            entity.setPredicate(fact.predicate());
            entity.setObjectValue(fact.objectValue());
            entity.setConfidence(fact.confidence());
            entity.setTrust(fact.trust());
            entity.setUseCount(0);
            entity.setExtractedBy(fact.extractedBy());
            entity.setOwnerKey(ownerKey);
            entity.setScope(scope);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setDeleted(0);
            factMapper.insert(entity);
            return entity.getId();
        }
    }

    private String normalizeScope(String scope) {
        return MemoryScope.PERSONAL.equals(scope) || MemoryScope.GLOBAL.equals(scope)
                ? scope : MemoryScope.TEAM;
    }

    private String normalizeOwner(String ownerKey, String scope) {
        return MemoryScope.PERSONAL.equals(scope) && ownerKey != null ? ownerKey : "";
    }

    private record ProjectedFact(ExtractedFact fact, String ownerKey, String scope) {}

    /**
     * Apply exponential time decay to trust score.
     * Formula: trust * 2^(-daysSinceLastUpdate / halfLifeDays)
     * Clamped to [0, 1].
     */
    private double applyTimeDecay(Double currentTrust, LocalDateTime lastUpdate, LocalDateTime now) {
        if (currentTrust == null) return 0.5;
        if (lastUpdate == null) return currentTrust;
        int halfLifeDays = properties.getFact().getTrustHalfLifeDays();
        if (halfLifeDays <= 0) return currentTrust; // decay disabled
        long daysDiff = java.time.Duration.between(lastUpdate, now).toDays();
        if (daysDiff <= 0) return currentTrust;
        double decayed = currentTrust * Math.pow(2.0, -(double) daysDiff / halfLifeDays);
        return Math.max(0.0, Math.min(1.0, decayed));
    }
}
