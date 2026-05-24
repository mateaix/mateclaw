package vip.mate.agent.progress;

import java.util.Locale;

/**
 * Status of a single step in the conversation-scoped progress ledger.
 *
 * <p>Kept deliberately small — four states cover the workflow patterns we
 * see in long multi-step agent tasks (research one item at a time, draft a
 * document section by section, etc.) without inviting bikeshedding on
 * intermediate states. The wire form is the lowercase enum name; the tool's
 * {@code status} parameter accepts case-insensitive input.
 */
public enum ProgressStatus {

    /** Step is known to be needed but not yet started. */
    PENDING,

    /** Currently being worked on. */
    IN_PROGRESS,

    /** Finished and verified by the agent. */
    DONE,

    /** Cannot continue — note must explain why so the user / next pass can intervene. */
    BLOCKED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a model-supplied status string. Tolerates case differences,
     * hyphens, and spaces (the model often writes "in progress" or
     * "in-progress" — both map to {@link #IN_PROGRESS}).
     *
     * @return the matching status, or {@code null} when no match is found so
     *         the caller can surface a structured error back to the LLM.
     */
    public static ProgressStatus parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ProgressStatus s : values()) {
            if (s.name().equals(normalised)) {
                return s;
            }
        }
        return null;
    }
}
