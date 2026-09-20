package vip.mate.goal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.goal.service.GoalJsonAcceptanceService;
import vip.mate.goal.service.ManagedGoalJsonService;

/** Authenticated user surface, intentionally not a model tool. */
@RestController
@RequestMapping("/api/v1/goals/{goalId}/json-acceptance")
@RequiredArgsConstructor
public class GoalJsonAcceptanceController {
    private final GoalJsonAcceptanceService acceptance;
    private final ManagedGoalJsonService artifacts;
    private final vip.mate.goal.service.GoalJsonBindingService bindings;

    @GetMapping
    public R<GoalJsonAcceptanceService.View> get(@PathVariable Long goalId, Authentication auth) {
        return authenticated(auth, username -> acceptance.get(goalId, username));
    }

    @PutMapping("/requirements/{criterionKey}")
    public R<GoalJsonAcceptanceService.Requirement> configure(@PathVariable Long goalId, @PathVariable String criterionKey,
            @RequestBody GoalJsonAcceptanceService.ConfigureRequest request, Authentication auth) {
        return authenticated(auth, username -> acceptance.configure(goalId, criterionKey, request, username));
    }

    @GetMapping("/artifacts")
    public R<java.util.List<ManagedGoalJsonService.Slot>> artifacts(@PathVariable Long goalId, Authentication auth) {
        return authenticated(auth, username -> artifacts.list(goalId, username));
    }

    @PostMapping("/artifacts/{slot}")
    public R<ManagedGoalJsonService.Artifact> publish(@PathVariable Long goalId, @PathVariable String slot,
            @RequestBody ManagedGoalJsonService.PublishRequest request, Authentication auth) {
        return authenticated(auth, username -> artifacts.publish(goalId, slot, request, username));
    }

    @GetMapping("/artifacts/versions/{artifactId}")
    public R<ManagedGoalJsonService.Content> version(@PathVariable Long goalId, @PathVariable String artifactId, Authentication auth) {
        return authenticated(auth, username -> artifacts.read(goalId, artifactId, username));
    }

    @GetMapping("/snapshot")
    public R<vip.mate.goal.service.GoalJsonBindingService.Snapshot> snapshot(@PathVariable Long goalId, Authentication auth) {
        return authenticated(auth, username -> bindings.snapshot(goalId, username));
    }

    @GetMapping("/checks")
    public R<java.util.List<vip.mate.goal.service.GoalJsonBindingService.State>> checks(@PathVariable Long goalId, Authentication auth) {
        return authenticated(auth, username -> bindings.state(goalId, username));
    }

    @PostMapping("/checks/{criterionKey}")
    public R<vip.mate.goal.service.GoalJsonBindingService.Check> check(@PathVariable Long goalId, @PathVariable String criterionKey,
            @RequestBody vip.mate.goal.service.GoalJsonBindingService.CheckRequest request, Authentication auth) {
        return authenticated(auth, username -> bindings.check(goalId, criterionKey, request, username));
    }

    private <T> R<T> authenticated(Authentication auth, java.util.function.Function<String, T> operation) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Long userId)) {
            throw new vip.mate.exception.MateClawException(401, "Authenticated account ID required");
        }
        return R.ok(acceptance.withAuthenticatedUser(userId, auth.getName(), operation));
    }
}
