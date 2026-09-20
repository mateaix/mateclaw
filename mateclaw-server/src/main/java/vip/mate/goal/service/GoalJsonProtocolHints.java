package vip.mate.goal.service;

/** Stable runtime guidance; user-controlled requirement text is retrieved through authorized tools. */
public final class GoalJsonProtocolHints {
    private GoalJsonProtocolHints() { }
    public static final String INSTRUCTIONS = """
            This goal has user-selected managed JSON acceptance requirements. Before claiming completion,
            call getManagedGoalJsonSlots to read current requirements and generations. Reuse a current version
            when it satisfies the request; call checkManagedGoalJson on that version if its binding is missing
            or outdated. Publish with publishManagedGoalJson only when content needs changing or the version
            is unusable. Each publication consumes one of 32 versions; a retry does not require a new version,
            and existing versions can still be checked at the limit. Every requirement needs a current binding
            using its exact revision, artifact ID and generation. Publishing a version alone is not a check.
            A new version, an edited requirement or goal definition, or expiry invalidates earlier bindings.
            Reload after conflicts and check current versions; do not invent PASS results, overwrite blindly,
            or substitute ordinary file checks or textual claims. Existing semantic criteria still apply.
            Only the platform's committed Goal status establishes completion. If runtime identity or access
            is unavailable, report the precise missing access instead of claiming success.
            """;
}
