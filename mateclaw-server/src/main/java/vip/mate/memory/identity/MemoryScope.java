package vip.mate.memory.identity;

/**
 * Visibility scope for a memory row (workspace file, fact, recall).
 *
 * <ul>
 *   <li>{@link #PERSONAL} — only the matching {@code owner_key} can read it.
 *       Conversation-derived memory defaults here.</li>
 *   <li>{@link #TEAM} — everyone using the agent can read it. Agent config /
 *       persona files (AGENTS.md, SOUL.md, PROFILE.md) and legacy rows live
 *       here.</li>
 *   <li>{@link #GLOBAL} — always visible. Reserved for agent-creator preset
 *       facts.</li>
 * </ul>
 *
 * Stored as a plain string column ({@code scope}) rather than a DB enum so the
 * H2 / MySQL migrations stay dialect-neutral.
 *
 * @author MateClaw Team
 */
public final class MemoryScope {

    public static final String PERSONAL = "PERSONAL";
    public static final String TEAM = "TEAM";
    public static final String GLOBAL = "GLOBAL";

    private MemoryScope() {
    }

    /** A scope is "shared" (visible to every owner) when it is TEAM or GLOBAL. */
    public static boolean isShared(String scope) {
        return TEAM.equals(scope) || GLOBAL.equals(scope);
    }
}
