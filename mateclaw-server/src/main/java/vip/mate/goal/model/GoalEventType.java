package vip.mate.goal.model;

/**
 * String constants for {@link GoalEventEntity#getEventType()}.
 *
 * <p>Kept as constants (not an enum) because the column is queried by
 * MyBatis Plus as a plain string, and SSE event payloads serialize the
 * value verbatim — having the same literal in code, DB, and wire format
 * eliminates one source of accidental drift.
 */
public final class GoalEventType {

    /** Goal created by the user or by the agent via setGoal tool. */
    public static final String CREATED = "created";

    /** One evaluation pass completed; carries score/gap/decision. */
    public static final String EVALUATED = "evaluated";

    /** Auto-followup prompt was injected for the next reasoning loop. */
    public static final String FOLLOWUP_INJECTED = "followup_injected";

    /** Evaluator judged completion; goal status flipped to completed. */
    public static final String COMPLETED = "completed";

    /** turn_budget or llm_call_budget consumed; status flipped to exhausted. */
    public static final String EXHAUSTED = "exhausted";

    /** User paused active goal. */
    public static final String PAUSED = "paused";

    /** User resumed a paused goal. */
    public static final String RESUMED = "resumed";

    /** User abandoned the goal; final state. */
    public static final String ABANDONED = "abandoned";

    /** Sub-criterion appended without restarting the goal. */
    public static final String CRITERION_ADDED = "criterion_added";

    private GoalEventType() {}
}
