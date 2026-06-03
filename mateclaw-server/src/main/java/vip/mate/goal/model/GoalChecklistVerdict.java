package vip.mate.goal.model;

import java.util.List;

/**
 * Evaluator output for the <b>verdict</b> round — applied once a goal's
 * checklist already exists.
 *
 * <p>The evaluator only takes a position on existing criteria by id; it does
 * not re-emit the criterion text. The service merges each {@link CriterionVerdict}
 * into the persistent {@code List<GoalCriterion>} by id (text and untouched
 * criteria are preserved), then derives completion from "all passed".
 *
 * <p>This is a per-round delta — never the full outward-facing checklist.
 * Outward payloads always carry the full {@code GoalResponse.criteria} array.
 */
public record GoalChecklistVerdict(
        List<CriterionVerdict> criterionVerdicts,
        String summary) {

    /** Per-criterion delta: latest passed state + evidence, keyed by id. */
    public record CriterionVerdict(String id, boolean passed, String evidence) {
    }
}
