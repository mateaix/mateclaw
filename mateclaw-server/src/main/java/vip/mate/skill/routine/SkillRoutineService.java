package vip.mate.skill.routine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-facing reads and decisions over mined routine candidates.
 *
 * <p>Separate from {@link SkillRoutineMiner} and {@link SkillRoutinePromoter}
 * because those are unattended batch passes, while everything here is a person
 * looking at what the system inferred about their habits and accepting or
 * rejecting it. That review matters: a routine promoted from a misread pattern
 * becomes a skill the agent consults on every similar request, so the operator
 * needs to see candidates before they qualify, not only after.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRoutineService {

    private final SkillRoutineCandidateMapper candidateMapper;
    private final SkillRoutinePromoter promoter;
    private final SkillRoutineProperties properties;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Candidates for the admin list, newest activity first.
     *
     * @param status optional filter — {@code observing} / {@code promoted} /
     *               {@code dismissed}; {@code null} or blank returns all
     * @param limit  maximum rows
     */
    public List<Map<String, Object>> list(String status, int limit) {
        return list(status, limit, 1L);
    }

    public List<Map<String, Object>> list(String status, int limit, Long workspaceId) {
        LambdaQueryWrapper<SkillRoutineCandidateEntity> q =
                new LambdaQueryWrapper<SkillRoutineCandidateEntity>()
                        .eq(SkillRoutineCandidateEntity::getWorkspaceId, normalizeWorkspaceId(workspaceId))
                        .orderByDesc(SkillRoutineCandidateEntity::getLastSeenAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        if (status != null && !status.isBlank()) {
            q.eq(SkillRoutineCandidateEntity::getStatus, status.strip().toLowerCase());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillRoutineCandidateEntity row : candidateMapper.selectList(q)) {
            out.add(toView(row));
        }
        return out;
    }

    /** Promotion thresholds, so the UI can show how far a candidate has to go. */
    public Map<String, Object> gates() {
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("minOccurrences", properties.getMinOccurrences());
        gates.put("minDistinctDays", properties.getMinDistinctDays());
        gates.put("enabled", properties.isEnabled());
        return gates;
    }

    /**
     * Reject a candidate. Mining skips dismissed signatures on every later
     * sweep, so this is permanent until the operator reopens it — without that
     * the next nightly pass would simply re-detect the same pattern.
     */
    public Map<String, Object> dismiss(Long id) {
        return dismiss(id, 1L);
    }

    public Map<String, Object> dismiss(Long id, Long workspaceId) {
        SkillRoutineCandidateEntity row = require(id, workspaceId);
        row.setStatus(SkillRoutineCandidateEntity.STATUS_DISMISSED);
        candidateMapper.updateById(row);
        log.info("[SkillRoutine] Candidate {} ('{}') dismissed by operator", id, row.getSignature());
        return toView(row);
    }

    /** Put a dismissed candidate back under observation. */
    public Map<String, Object> reopen(Long id) {
        return reopen(id, 1L);
    }

    public Map<String, Object> reopen(Long id, Long workspaceId) {
        SkillRoutineCandidateEntity row = require(id, workspaceId);
        row.setStatus(SkillRoutineCandidateEntity.STATUS_OBSERVING);
        candidateMapper.updateById(row);
        log.info("[SkillRoutine] Candidate {} ('{}') reopened by operator", id, row.getSignature());
        return toView(row);
    }

    /**
     * Promote a candidate now, bypassing the recurrence gates.
     *
     * <p>The gates exist to keep the unattended pass from acting on thin
     * evidence; an operator looking at the candidate has better judgement than
     * the thresholds, so an explicit request is allowed through.
     */
    public Map<String, Object> promoteNow(Long id) {
        return promoteNow(id, 1L);
    }

    public Map<String, Object> promoteNow(Long id, Long workspaceId) {
        SkillRoutineCandidateEntity row = require(id, workspaceId);
        if (SkillRoutineCandidateEntity.STATUS_PROMOTED.equals(row.getStatus())) {
            throw new IllegalStateException("Routine already promoted to skill '"
                    + row.getPromotedSkillName() + "'");
        }
        boolean ok = promoter.promoteCandidate(row);
        Map<String, Object> view = toView(require(id, workspaceId));
        view.put("promoted", ok);
        return view;
    }

    private SkillRoutineCandidateEntity require(Long id, Long workspaceId) {
        SkillRoutineCandidateEntity row = candidateMapper.selectOne(
                new LambdaQueryWrapper<SkillRoutineCandidateEntity>()
                        .eq(SkillRoutineCandidateEntity::getId, id)
                        .eq(SkillRoutineCandidateEntity::getWorkspaceId, normalizeWorkspaceId(workspaceId)));
        if (row == null) {
            throw new IllegalArgumentException("Routine candidate " + id + " not found");
        }
        return row;
    }

    private Map<String, Object> toView(SkillRoutineCandidateEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Snowflake ids as strings: 19 digits exceed JS Number precision.
        m.put("id", String.valueOf(row.getId()));
        m.put("agentId", row.getAgentId() == null ? null : String.valueOf(row.getAgentId()));
        m.put("signature", row.getSignature());
        m.put("representativeText", row.getRepresentativeText());
        m.put("occurrenceCount", row.getOccurrenceCount());
        m.put("distinctDayCount", row.getDistinctDayCount());
        m.put("status", row.getStatus());
        m.put("promotedSkillName", row.getPromotedSkillName());
        m.put("firstSeenAt", row.getFirstSeenAt() == null ? null : row.getFirstSeenAt().format(FMT));
        m.put("lastSeenAt", row.getLastSeenAt() == null ? null : row.getLastSeenAt().format(FMT));
        m.put("qualified", meetsGates(row));
        return m;
    }

    private boolean meetsGates(SkillRoutineCandidateEntity row) {
        int occurrences = row.getOccurrenceCount() == null ? 0 : row.getOccurrenceCount();
        int days = row.getDistinctDayCount() == null ? 0 : row.getDistinctDayCount();
        return occurrences >= properties.getMinOccurrences()
                && days >= properties.getMinDistinctDays()
                && row.getLastSeenAt() != null
                && !row.getLastSeenAt().isBefore(java.time.LocalDateTime.now()
                        .minusDays(Math.max(1, properties.getLookbackDays())));
    }

    private static long normalizeWorkspaceId(Long workspaceId) {
        return workspaceId != null && workspaceId > 0 ? workspaceId : 1L;
    }
}
