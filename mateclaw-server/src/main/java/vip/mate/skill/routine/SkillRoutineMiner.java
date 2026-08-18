package vip.mate.skill.routine;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.common.text.SecretRedactor;
import vip.mate.common.text.Shingles;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects requests the user makes habitually, by clustering the opening
 * message of every recent conversation and counting how many distinct
 * conversations and distinct days each cluster spans.
 *
 * <h2>Why a separate pass</h2>
 * Recurrence is structurally invisible to the post-turn reflection reviewer:
 * it sees exactly one conversation window, in which a habitual request is
 * indistinguishable from a one-off task. Reflection is right to decline
 * writing a skill for a one-off narrative — which means the very signal the
 * user cares about ("I ask this every week, just know how to do it") can never
 * reach it. This pass supplies the missing dimension by looking across
 * sessions, where repetition is the evidence.
 *
 * <h2>Recomputed, not accumulated</h2>
 * Every sweep recomputes each cluster's statistics from scratch over the
 * lookback window and writes the result, rather than incrementing counters.
 * That makes repeated sweeps idempotent (a re-run cannot inflate counts) and
 * lets a routine the user abandoned decay back out of the window on its own.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRoutineMiner {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final SkillRoutineCandidateMapper candidateMapper;
    private final WorkspaceMapper workspaceMapper;
    private final SkillRoutineProperties properties;
    private final ObjectMapper objectMapper;

    /** Conversation ids per {@code IN} clause when loading openers. */
    private static final int OPENER_BATCH_SIZE = 200;

    /** URLs, filesystem paths, and long digit runs carry no routine identity. */
    private static final Pattern URL_RE = Pattern.compile("https?://\\S+");
    private static final Pattern PATH_RE = Pattern.compile("(?:[A-Za-z]:)?[/\\\\][\\w./\\\\-]{3,}");
    private static final Pattern DIGITS_RE = Pattern.compile("\\d+");
    /** Everything that is not a letter, CJK character, or space. */
    private static final Pattern NOISE_RE = Pattern.compile("[^\\p{IsHan}\\p{IsAlphabetic} ]+");
    private static final Pattern SPACE_RE = Pattern.compile("\\s+");

    /**
     * One conversation's opening request, already normalized and shingled.
     *
     * @param conversationId external conversation identifier
     * @param agentId        owning agent
     * @param workspaceId    owning workspace, may be {@code null}
     * @param rawOpener      verbatim opener, kept for the synthesis prompt
     * @param normalized     normalized opener; the cluster signature source
     * @param shingles       shingle set of {@link #normalized}
     * @param seenAt         when the conversation started
     */
    record Opener(String conversationId,
                  Long agentId,
                  Long workspaceId,
                  String rawOpener,
                  String normalized,
                  Set<String> shingles,
                  LocalDateTime seenAt) {
    }

    /** A group of openers judged to be the same request. */
    static final class Cluster {
        private final List<Opener> members = new ArrayList<>();

        Cluster(Opener seed) {
            members.add(seed);
        }

        Opener seed() {
            return members.get(0);
        }

        List<Opener> members() {
            return members;
        }

        /** Most recent member — the freshest phrasing of the routine. */
        Opener latest() {
            Opener best = members.get(0);
            for (Opener o : members) {
                if (o.seenAt() != null
                        && (best.seenAt() == null || o.seenAt().isAfter(best.seenAt()))) {
                    best = o;
                }
            }
            return best;
        }

        int distinctDays() {
            Set<LocalDate> days = new HashSet<>();
            for (Opener o : members) {
                if (o.seenAt() != null) {
                    days.add(o.seenAt().toLocalDate());
                }
            }
            return days.size();
        }
    }

    /**
     * Run one mining sweep across every agent with recent activity.
     *
     * @return number of candidate rows written or refreshed
     */
    public int mine() {
        return mineAll();
    }

    /** Mine every workspace. This entry point is reserved for the scheduler. */
    public int mineAll() {
        List<Long> workspaceIds = workspaceMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceEntity>().select(WorkspaceEntity::getId))
                .stream()
                .map(WorkspaceEntity::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (workspaceIds.isEmpty()) {
            workspaceIds = List.of(1L);
        }
        int written = 0;
        for (Long workspaceId : workspaceIds) {
            try {
                written += mineInternal(workspaceId);
            } catch (Exception e) {
                log.warn("[SkillRoutine] Mining failed for workspace {}: {}", workspaceId, e.getMessage());
            }
        }
        return written;
    }

    /**
     * Mine one workspace for an admin request. Missing/invalid scope fails
     * closed to the legacy default workspace instead of widening to all tenants.
     */
    public int mine(Long workspaceId) {
        long scopedWorkspaceId = workspaceId != null && workspaceId > 0 ? workspaceId : 1L;
        return mineInternal(scopedWorkspaceId);
    }

    private int mineInternal(Long workspaceId) {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, properties.getLookbackDays()));
        expireStaleCandidates(workspaceId, cutoff);
        List<ConversationEntity> conversations = loadRecentConversations(cutoff, workspaceId);
        if (conversations.isEmpty()) {
            return 0;
        }
        Map<String, String> openersByConversation = loadOpeners(conversations);
        if (openersByConversation.isEmpty()) {
            return 0;
        }

        // Group by agent — a routine belongs to the agent the user runs it on.
        Map<Long, List<Opener>> byAgent = new LinkedHashMap<>();
        for (ConversationEntity conv : conversations) {
            if (conv.getAgentId() == null || conv.getConversationId() == null) {
                continue;
            }
            // Redact before anything downstream keeps a copy. This text is
            // persisted into the candidate table, rendered in the admin list,
            // and sent to the synthesis model — three new places a credential
            // pasted into a chat would otherwise come to rest.
            String raw = SecretRedactor.redact(openersByConversation.get(conv.getConversationId()));
            String normalized = normalize(raw);
            if (normalized.length() < properties.getMinOpenerChars()) {
                continue;
            }
            Set<String> shingles = Shingles.of(normalized);
            if (shingles.isEmpty()) {
                continue;
            }
            byAgent.computeIfAbsent(conv.getAgentId(), k -> new ArrayList<>())
                    .add(new Opener(conv.getConversationId(), conv.getAgentId(), conv.getWorkspaceId(),
                            raw, normalized, shingles, conversationStart(conv)));
        }

        int written = 0;
        for (Map.Entry<Long, List<Opener>> entry : byAgent.entrySet()) {
            for (Cluster cluster : cluster(entry.getValue())) {
                if (cluster.members().size() < 2) {
                    // A singleton carries no recurrence evidence; persisting it
                    // would fill the table with one row per conversation.
                    continue;
                }
                if (upsert(entry.getKey(), cluster)) {
                    written++;
                }
            }
        }
        if (written > 0) {
            log.info("[SkillRoutine] Mining sweep refreshed {} candidate(s) across {} agent(s)",
                    written, byAgent.size());
        }
        return written;
    }

    /**
     * Evidence is a sliding window, not a lifetime counter. Once the newest
     * occurrence falls outside the lookback window, clear the automatic
     * promotion gates while retaining the row and operator decision history.
     */
    private void expireStaleCandidates(Long workspaceId, LocalDateTime cutoff) {
        if (workspaceId == null || workspaceId <= 0) {
            return;
        }
        candidateMapper.update(null, new LambdaUpdateWrapper<SkillRoutineCandidateEntity>()
                .eq(SkillRoutineCandidateEntity::getWorkspaceId, workspaceId)
                .eq(SkillRoutineCandidateEntity::getStatus, SkillRoutineCandidateEntity.STATUS_OBSERVING)
                .and(w -> w.isNull(SkillRoutineCandidateEntity::getLastSeenAt)
                        .or().lt(SkillRoutineCandidateEntity::getLastSeenAt, cutoff))
                .set(SkillRoutineCandidateEntity::getOccurrenceCount, 0)
                .set(SkillRoutineCandidateEntity::getDistinctDayCount, 0)
                .set(SkillRoutineCandidateEntity::getSampleConversations, "[]"));
    }

    // ==================== Loading ====================

    private List<ConversationEntity> loadRecentConversations(LocalDateTime cutoff, Long workspaceId) {
        Page<ConversationEntity> page = new Page<>(1, Math.max(1, properties.getMaxConversationsPerRun()), false);
        LambdaQueryWrapper<ConversationEntity> q = new LambdaQueryWrapper<ConversationEntity>()
                .select(ConversationEntity::getConversationId, ConversationEntity::getAgentId,
                        ConversationEntity::getWorkspaceId, ConversationEntity::getCreateTime,
                        ConversationEntity::getLastActiveTime)
                .isNotNull(ConversationEntity::getAgentId)
                .ge(ConversationEntity::getLastActiveTime, cutoff)
                .orderByDesc(ConversationEntity::getLastActiveTime);
        if (workspaceId != null && workspaceId > 0) {
            q.eq(ConversationEntity::getWorkspaceId, workspaceId);
        }
        return conversationMapper.selectPage(page, q).getRecords();
    }

    /**
     * First user message of each conversation, keyed by conversation id.
     *
     * <p>Loads user messages in batched {@code IN} clauses and keeps the
     * lowest-id row per conversation. Cost scales with the number of user
     * messages in the scanned conversations, which the caller bounds through
     * {@code maxConversationsPerRun}; this runs as a nightly sweep, not on a
     * request path.
     */
    private Map<String, String> loadOpeners(List<ConversationEntity> conversations) {
        List<String> ids = new ArrayList<>();
        for (ConversationEntity c : conversations) {
            if (c.getConversationId() != null) {
                ids.add(c.getConversationId());
            }
        }
        Map<String, String> openers = new HashMap<>();
        for (int i = 0; i < ids.size(); i += OPENER_BATCH_SIZE) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + OPENER_BATCH_SIZE));
            List<MessageEntity> rows;
            try {
                rows = messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                        .select(MessageEntity::getConversationId, MessageEntity::getContent)
                        .eq(MessageEntity::getRole, "user")
                        .in(MessageEntity::getConversationId, batch)
                        .orderByAsc(MessageEntity::getId));
            } catch (Exception e) {
                log.warn("[SkillRoutine] Opener batch load failed: {}", e.getMessage());
                continue;
            }
            for (MessageEntity m : rows) {
                if (m.getConversationId() == null || m.getContent() == null) {
                    continue;
                }
                // Ascending id, so the first row seen per conversation is its opener.
                openers.putIfAbsent(m.getConversationId(), m.getContent());
            }
        }
        return openers;
    }

    private static LocalDateTime conversationStart(ConversationEntity conv) {
        return conv.getCreateTime() != null ? conv.getCreateTime() : conv.getLastActiveTime();
    }

    // ==================== Normalization + clustering ====================

    /**
     * Strip everything that varies between two runs of the same routine —
     * URLs, paths, numbers, punctuation, case — leaving the stable intent.
     * "generate the 2026-08-04 report" and "generate the 2026-08-05 report"
     * must normalize to the same text or they will never cluster.
     */
    String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.strip();
        int max = Math.max(20, properties.getMaxOpenerChars());
        if (text.length() > max) {
            text = text.substring(0, max);
        }
        text = URL_RE.matcher(text).replaceAll(" ");
        text = PATH_RE.matcher(text).replaceAll(" ");
        text = DIGITS_RE.matcher(text).replaceAll(" ");
        text = text.toLowerCase();
        text = NOISE_RE.matcher(text).replaceAll(" ");
        return SPACE_RE.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Greedy single-pass clustering against each existing cluster's seed.
     *
     * <p>Seed comparison (rather than full linkage) keeps clusters tight: a
     * chain of pairwise-similar openers cannot drift into one blob where the
     * first and last members share nothing.
     */
    List<Cluster> cluster(List<Opener> openers) {
        List<Cluster> clusters = new ArrayList<>();
        double threshold = properties.getSimilarityThreshold();
        for (Opener opener : openers) {
            Cluster match = null;
            double best = threshold;
            for (Cluster c : clusters) {
                double score = Shingles.jaccard(opener.shingles(), c.seed().shingles());
                if (score >= best) {
                    best = score;
                    match = c;
                }
            }
            if (match == null) {
                clusters.add(new Cluster(opener));
            } else {
                match.members().add(opener);
            }
        }
        return clusters;
    }

    // ==================== Persistence ====================

    /** @return {@code true} when a row was inserted or refreshed */
    private boolean upsert(Long agentId, Cluster cluster) {
        Opener seed = cluster.seed();
        Opener latest = cluster.latest();
        String signature = truncate(seed.normalized(), 512);
        String hash = SecureUtil.sha256(signature);

        SkillRoutineCandidateEntity existing = candidateMapper.selectOne(
                new LambdaQueryWrapper<SkillRoutineCandidateEntity>()
                        .eq(SkillRoutineCandidateEntity::getAgentId, agentId)
                        .eq(SkillRoutineCandidateEntity::getSignatureHash, hash)
                        .last("LIMIT 1"));
        if (existing != null
                && SkillRoutineCandidateEntity.STATUS_DISMISSED.equals(existing.getStatus())) {
            // The operator rejected this routine; never resurrect it.
            return false;
        }

        SkillRoutineCandidateEntity row = existing == null ? new SkillRoutineCandidateEntity() : existing;
        row.setAgentId(agentId);
        row.setWorkspaceId(seed.workspaceId());
        row.setSignature(signature);
        row.setSignatureHash(hash);
        row.setRepresentativeText(truncate(latest.rawOpener(), 2048));
        row.setSampleConversations(serializeSamples(cluster));
        row.setOccurrenceCount(cluster.members().size());
        row.setDistinctDayCount(cluster.distinctDays());
        row.setFirstSeenAt(earliest(cluster));
        row.setLastSeenAt(latest.seenAt());
        if (row.getStatus() == null) {
            row.setStatus(SkillRoutineCandidateEntity.STATUS_OBSERVING);
        }
        try {
            if (existing == null) {
                candidateMapper.insert(row);
            } else {
                candidateMapper.updateById(row);
            }
            return true;
        } catch (Exception e) {
            log.warn("[SkillRoutine] Candidate upsert failed for agent={} signature='{}': {}",
                    agentId, signature, e.getMessage());
            return false;
        }
    }

    private String serializeSamples(Cluster cluster) {
        List<String> ids = new ArrayList<>();
        // Newest first: the synthesis prompt should see current phrasing.
        List<Opener> members = new ArrayList<>(cluster.members());
        members.sort((a, b) -> {
            if (a.seenAt() == null) return 1;
            if (b.seenAt() == null) return -1;
            return b.seenAt().compareTo(a.seenAt());
        });
        for (Opener o : members) {
            if (ids.size() >= properties.getMaxSamplesPerCandidate()) {
                break;
            }
            ids.add(o.conversationId());
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static LocalDateTime earliest(Cluster cluster) {
        LocalDateTime best = null;
        for (Opener o : cluster.members()) {
            if (o.seenAt() != null && (best == null || o.seenAt().isBefore(best))) {
                best = o.seenAt();
            }
        }
        return best;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
