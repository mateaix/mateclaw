package vip.mate.workspace.core.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative role &rarr; capability mapping.
 * <p>
 * The frontend treats the {@code /access} response as the single source of truth
 * and does NOT keep a local copy of this table. Updating role permissions only
 * requires editing this file.
 * <p>
 * Role hierarchy is additive: each level inherits everything from the level below.
 */
public final class RoleCapabilities {

    public static final String ROLE_VIEWER = "viewer";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_OWNER = "owner";

    private static final Map<String, Set<String>> ROLE_TO_CAPS;

    static {
        Set<String> viewer = new LinkedHashSet<>();
        viewer.add(Capability.CHAT);
        viewer.add(Capability.VIEW_WIKI);

        Set<String> member = new LinkedHashSet<>(viewer);
        member.add(Capability.VIEW_MEMORY);
        member.add(Capability.VIEW_DASHBOARD);
        member.add(Capability.MANAGE_WIKI);
        member.add(Capability.MANAGE_AGENTS);

        Set<String> admin = new LinkedHashSet<>(member);
        admin.add(Capability.MANAGE_SKILLS);
        admin.add(Capability.MANAGE_CHANNELS);
        admin.add(Capability.MANAGE_MODELS);
        admin.add(Capability.MANAGE_SECURITY);
        admin.add(Capability.MANAGE_SETTINGS);

        // Owner currently has the same capability set as admin. Owner-only actions
        // (workspace deletion, transferring owner role) are enforced at the
        // service layer via role-level comparison, not capabilities.
        Set<String> owner = new LinkedHashSet<>(admin);

        ROLE_TO_CAPS = Map.of(
                ROLE_VIEWER, Collections.unmodifiableSet(viewer),
                ROLE_MEMBER, Collections.unmodifiableSet(member),
                ROLE_ADMIN, Collections.unmodifiableSet(admin),
                ROLE_OWNER, Collections.unmodifiableSet(owner)
        );
    }

    private RoleCapabilities() {}

    /**
     * Returns the capability set for a given role. Unknown roles get an empty set
     * (default deny).
     */
    public static Set<String> forRole(String role) {
        if (role == null) {
            return Set.of();
        }
        Set<String> caps = ROLE_TO_CAPS.get(role.toLowerCase());
        return caps != null ? caps : Set.of();
    }

    public static int roleLevel(String role) {
        if (role == null) return 0;
        return switch (role.toLowerCase()) {
            case ROLE_OWNER -> 4;
            case ROLE_ADMIN -> 3;
            case ROLE_MEMBER -> 2;
            case ROLE_VIEWER -> 1;
            default -> 0;
        };
    }
}
