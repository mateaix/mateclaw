package vip.mate.goal.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared (de)serialization and merge helpers for a goal's checklist stored
 * as JSON text in {@code mate_agent_goal.criteria}.
 *
 * <p>Centralizes the String JSON ↔ {@code List<GoalCriterion>} boundary so the
 * evaluator, service and node never reimplement parsing. Parse failures fail
 * soft to an empty list (logged) rather than throwing — a corrupt column must
 * never break a chat turn or an API response.
 */
public final class GoalCriteriaCodec {

    private static final Logger log = LoggerFactory.getLogger(GoalCriteriaCodec.class);
    private static final TypeReference<List<GoalCriterion>> LIST_TYPE = new TypeReference<>() {
    };

    private GoalCriteriaCodec() {
    }

    /** Parse the JSON column into a mutable list; empty list on null/blank/corrupt. */
    public static List<GoalCriterion> parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<GoalCriterion> parsed = mapper.readValue(json, LIST_TYPE);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[GoalCriteria] failed to parse criteria JSON, treating as empty: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Serialize a checklist to JSON text; {@code null} for a null list. */
    public static String serialize(List<GoalCriterion> criteria, ObjectMapper mapper) {
        if (criteria == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(criteria);
        } catch (JsonProcessingException e) {
            log.warn("[GoalCriteria] failed to serialize criteria, storing null: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Merge a per-round verdict delta into the full checklist by id. Criteria
     * absent from the delta retain their state unless their pass lacks evidence.
     * The criterion text is always kept from the existing item (the verdict never carries text).
     * Duplicate verdict ids are ambiguous and rejected instead of taking the last value.
     */
    public static List<GoalCriterion> merge(List<GoalCriterion> existing,
                                            List<GoalChecklistVerdict.CriterionVerdict> verdicts) {
        if (existing == null || existing.isEmpty()) {
            return existing == null ? new ArrayList<>() : existing;
        }
        Map<String, GoalChecklistVerdict.CriterionVerdict> byId = new LinkedHashMap<>();
        if (verdicts != null) {
            for (GoalChecklistVerdict.CriterionVerdict v : verdicts) {
                if (v != null && v.id() != null) {
                    if (byId.putIfAbsent(v.id(), v) != null) {
                        throw new IllegalArgumentException("Duplicate criterion verdict id");
                    }
                }
            }
        }
        List<GoalCriterion> merged = new ArrayList<>(existing.size());
        for (GoalCriterion c : existing) {
            GoalChecklistVerdict.CriterionVerdict v = byId.get(c.id());
            GoalCriterion candidate = v == null ? c
                    : new GoalCriterion(c.id(), c.text(), v.passed(),
                            v.evidence() != null ? v.evidence() : "");
            // A model boolean alone cannot satisfy even the legacy semantic
            // checklist. This checks presence, not truth or execution provenance.
            merged.add(candidate.passed() && !hasEvidence(candidate)
                    ? new GoalCriterion(candidate.id(), candidate.text(), false, candidate.evidence())
                    : candidate);
        }
        return merged;
    }

    /** True only when the list is non-empty and every criterion is passed with nonblank evidence. */
    public static boolean allPassed(List<GoalCriterion> criteria) {
        return criteria != null && !criteria.isEmpty()
                && criteria.stream().allMatch(c -> c != null && c.passed() && hasEvidence(c));
    }

    /** Criteria not yet passed (used for the continuation prompt + gap text). */
    public static List<GoalCriterion> remaining(List<GoalCriterion> criteria) {
        if (criteria == null) {
            return List.of();
        }
        return criteria.stream().filter(c -> !c.passed() || !hasEvidence(c)).toList();
    }

    private static boolean hasEvidence(GoalCriterion criterion) {
        return criterion.evidence() != null && !criterion.evidence().isBlank();
    }

    /** Reassign stable ids {@code C1..Cn} in list order. */
    public static List<GoalCriterion> reindex(List<GoalCriterion> criteria) {
        List<GoalCriterion> out = new ArrayList<>(criteria.size());
        int n = 1;
        for (GoalCriterion c : criteria) {
            out.add(new GoalCriterion("C" + n, c.text(), c.passed(), c.evidence() == null ? "" : c.evidence()));
            n++;
        }
        return out;
    }
}
