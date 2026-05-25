package vip.mate.system.featureflag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.system.featureflag.repository.FeatureFlagMapper;

import java.util.List;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Admin endpoints for runtime feature-flag toggling.
 *
 * <p>Authn / authz are delegated to the global security configuration:
 * the JWT filter populates the principal, and the controller method runs
 * inside the standard admin-role guard. Edit access should be restricted
 * to operators; everyday users have no business toggling these.
 *
 * @author MateClaw Team
 */
@RestController
@RequestMapping("/api/v1/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService service;
    private final FeatureFlagMapper mapper;

    /** Lists every flag currently registered, including disabled and whitelisted ones. */
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<FeatureFlagEntity>> list() {
        return R.ok(mapper.selectList(null));
    }

    /**
     * Updates one flag in place. Only fields explicitly set in the request
     * body are touched; unspecified fields preserve their current values.
     */
    @PutMapping("/{flagKey}")
    @RequireWorkspaceRole("admin")
    public R<Void> update(@PathVariable @NotBlank String flagKey,
                           @RequestBody UpdateRequest req) {
        FeatureFlagEntity flag = mapper.selectOne(
                new LambdaQueryWrapper<FeatureFlagEntity>()
                        .eq(FeatureFlagEntity::getFlagKey, flagKey));
        if (flag == null) {
            return R.fail("Unknown flag: " + flagKey);
        }
        if (req.getEnabled() != null) {
            flag.setEnabled(req.getEnabled());
        }
        if (req.getDescription() != null) {
            flag.setDescription(req.getDescription());
        }
        if (req.getWhitelistKbIds() != null) {
            flag.setWhitelistKbIds(req.getWhitelistKbIds());
        }
        if (req.getWhitelistUserIds() != null) {
            flag.setWhitelistUserIds(req.getWhitelistUserIds());
        }
        if (req.getRolloutPercent() != null) {
            flag.setRolloutPercent(req.getRolloutPercent());
        }
        mapper.updateById(flag);
        service.invalidate();
        return R.ok();
    }

    /** Body for {@link #update(String, UpdateRequest)}. */
    @Data
    public static class UpdateRequest {
        private Boolean enabled;
        private String description;
        private String whitelistKbIds;
        private String whitelistUserIds;
        @Min(0)
        @Max(100)
        private Integer rolloutPercent;
    }
}
