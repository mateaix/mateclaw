package vip.mate.workspace.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

/**
 * Response for {@code GET /api/v1/workspaces/&#123;id&#125;/access}.
 * <p>
 * The frontend calls this after switching workspace or when a 403 suggests
 * its cached capability set is stale.
 */
@Data
@AllArgsConstructor
public class WorkspaceAccessVO {

    private Long workspaceId;
    private String memberRole;
    private Boolean isGlobalAdmin;
    private String effectiveRole;
    private Set<String> capabilities;
}
