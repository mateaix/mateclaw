package vip.mate.skill.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import vip.mate.skill.lifecycle.SkillLifecycleService;
import vip.mate.skill.repository.SkillUsageStatMapper;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillUsageService {

    private final SkillUsageStatMapper mapper;
    /** Bubbles the load event up to {@code mate_skill.last_activity_at} for the lifecycle curator. */
    private final SkillLifecycleService lifecycleService;

    public void recordLoaded(ResolvedSkill skill, Long agentId, String conversationId,
                             String filePath, int tokenEstimate) {
        if (skill == null || skill.getName() == null || skill.getName().isBlank()) return;
        try {
            Long scopedAgentId = agentId != null ? agentId : 0L;
            String scopedConversationId = blankToEmpty(conversationId);
            LocalDateTime now = LocalDateTime.now();
            int updated = incrementExisting(skill, scopedAgentId, scopedConversationId,
                    filePath, tokenEstimate, now);
            if (updated == 0) {
                SkillUsageStatEntity row = new SkillUsageStatEntity();
                row.setSkillName(skill.getName());
                row.setSkillId(skill.getId());
                row.setAgentId(scopedAgentId);
                row.setConversationId(scopedConversationId);
                row.setLoadCount(1L);
                row.setLastLoadedAt(now);
                row.setLastFilePath(filePath);
                row.setLastTokenEstimate(tokenEstimate);
                row.setDeleted(0);
                try {
                    mapper.insert(row);
                } catch (DuplicateKeyException race) {
                    // Another parallel invocation inserted the same scoped row
                    // after our update missed it. Retry as one atomic update.
                    incrementExisting(skill, scopedAgentId, scopedConversationId,
                            filePath, tokenEstimate, now);
                }
            }
            // Mirror the activity anchor onto mate_skill so the lifecycle
            // curator's daily scan stays a single indexed select.
            lifecycleService.bumpActivity(skill.getId());
        } catch (Exception e) {
            log.debug("Failed to record skill usage for {}: {}", skill.getName(), e.getMessage());
        }
    }

    private int incrementExisting(ResolvedSkill skill, Long agentId, String conversationId,
                                  String filePath, int tokenEstimate, LocalDateTime now) {
        return mapper.update(null, new LambdaUpdateWrapper<SkillUsageStatEntity>()
                .set(SkillUsageStatEntity::getSkillId, skill.getId())
                .set(SkillUsageStatEntity::getLastLoadedAt, now)
                .set(SkillUsageStatEntity::getLastFilePath, filePath)
                .set(SkillUsageStatEntity::getLastTokenEstimate, tokenEstimate)
                .setSql("load_count = COALESCE(load_count, 0) + 1")
                .eq(SkillUsageStatEntity::getSkillName, skill.getName())
                .eq(SkillUsageStatEntity::getAgentId, agentId)
                .eq(SkillUsageStatEntity::getConversationId, conversationId));
    }

    public Set<String> recentLoadedSkillNames(Long agentId, int limit) {
        if (agentId == null || limit <= 0) return Set.of();
        try {
            List<SkillUsageStatEntity> rows = mapper.selectList(new LambdaQueryWrapper<SkillUsageStatEntity>()
                    .eq(SkillUsageStatEntity::getAgentId, agentId)
                    .isNotNull(SkillUsageStatEntity::getLastLoadedAt)
                    .orderByDesc(SkillUsageStatEntity::getLastLoadedAt)
                    .last("LIMIT " + Math.min(limit, 50)));
            return rows.stream()
                    .map(SkillUsageStatEntity::getSkillName)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        } catch (Exception e) {
            log.debug("Failed to read recent skill usage for agent {}: {}", agentId, e.getMessage());
            return Set.of();
        }
    }

    public Set<String> frequentlyLoadedSkillNames(int limit) {
        if (limit <= 0) return Set.of();
        try {
            List<SkillUsageStatEntity> rows = mapper.selectList(new LambdaQueryWrapper<SkillUsageStatEntity>()
                    .orderByDesc(SkillUsageStatEntity::getLoadCount)
                    .orderByDesc(SkillUsageStatEntity::getLastLoadedAt)
                    .last("LIMIT " + Math.min(limit, 50)));
            return rows.stream()
                    .map(SkillUsageStatEntity::getSkillName)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        } catch (Exception e) {
            log.debug("Failed to read frequent skill usage: {}", e.getMessage());
            return Set.of();
        }
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
