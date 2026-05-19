package vip.mate.skill.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            SkillUsageStatEntity row = mapper.selectOne(new LambdaQueryWrapper<SkillUsageStatEntity>()
                    .eq(SkillUsageStatEntity::getSkillName, skill.getName())
                    .eq(SkillUsageStatEntity::getAgentId, scopedAgentId)
                    .eq(SkillUsageStatEntity::getConversationId, scopedConversationId)
                    .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (row == null) {
                row = new SkillUsageStatEntity();
                row.setSkillName(skill.getName());
                row.setSkillId(skill.getId());
                row.setAgentId(scopedAgentId);
                row.setConversationId(scopedConversationId);
                row.setLoadCount(1L);
                row.setLastLoadedAt(now);
                row.setLastFilePath(filePath);
                row.setLastTokenEstimate(tokenEstimate);
                row.setDeleted(0);
                mapper.insert(row);
            } else {
                row.setSkillId(skill.getId());
                row.setLoadCount((row.getLoadCount() == null ? 0L : row.getLoadCount()) + 1);
                row.setLastLoadedAt(now);
                row.setLastFilePath(filePath);
                row.setLastTokenEstimate(tokenEstimate);
                mapper.updateById(row);
            }
            // Mirror the activity anchor onto mate_skill so the lifecycle
            // curator's daily scan stays a single indexed select.
            lifecycleService.bumpActivity(skill.getId());
        } catch (Exception e) {
            log.debug("Failed to record skill usage for {}: {}", skill.getName(), e.getMessage());
        }
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
