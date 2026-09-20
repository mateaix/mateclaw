package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.goal.service.ManagedGoalJsonService;

/** Runtime may produce versions, but only the authenticated user can configure requirements. */
@Component
@RequiredArgsConstructor
public class ManagedGoalJsonTool {
    private final ManagedGoalJsonService artifacts;
    private final ObjectMapper json;
    private final vip.mate.goal.service.GoalJsonBindingService bindings;

    @Tool(description = "Read the current conversation goal's managed JSON artifact slots and generations. "
            + "Only user-selected slots appear. Preserve generation strings exactly. This does not check or complete the goal.")
    public String getManagedGoalJsonSlots(ToolContext context) throws JsonProcessingException {
        return json.writeValueAsString(bindings.snapshotForRuntime(ChatOrigin.from(context)));
    }

    @Tool(description = "Publish a new immutable JSON object version to a user-selected slot of the current goal. "
            + "Read current slots first; use generation 0 for an empty slot. Maximum 1 MiB UTF-8 per version, "
            + "32 versions per goal, valid for 24 hours. Reload on generation conflict. "
            + "Publishing does not check requirements or complete the goal; workspace files and textual claims are not substitutes.")
    public String publishManagedGoalJson(
            @ToolParam(description = "An existing user-selected artifact slot") String artifactSlot,
            @ToolParam(description = "Exact current generation string, or 0 for an empty slot") String expectedGeneration,
            @ToolParam(description = "Raw strict JSON object content; not a file path") String jsonContent,
            ToolContext context) throws JsonProcessingException {
        Long generation;
        try { generation = Long.valueOf(expectedGeneration); }
        catch (RuntimeException invalid) { throw new vip.mate.exception.MateClawException(400, "A valid expectedGeneration is required"); }
        return json.writeValueAsString(artifacts.publishForRuntime(ChatOrigin.from(context), artifactSlot,
                new ManagedGoalJsonService.PublishRequest(generation, jsonContent)));
    }
    @Tool(description = "Run the trusted JSON fields recipe against an exact current managed version for one user requirement. "
            + "Use the requirement revision from getManagedGoalJsonSlots and artifact ID/generation from publication. "
            + "The server derives the result from stored bytes; it does not accept a caller PASS. "
            + "Every current user requirement needs a matching binding before goal completion; edits or new versions invalidate old bindings.")
    public String checkManagedGoalJson(
            @ToolParam(description = "Current user requirement key") String criterionKey,
            @ToolParam(description = "Exact current requirement revision string") String expectedRequirementRevision,
            @ToolParam(description = "Exact current managed artifact ID") String artifactId,
            @ToolParam(description = "Exact current slot generation string") String expectedGeneration,
            ToolContext context) throws JsonProcessingException {
        Long revision; Long generation;
        try { revision = Long.valueOf(expectedRequirementRevision); generation = Long.valueOf(expectedGeneration); }
        catch (RuntimeException invalid) { throw new vip.mate.exception.MateClawException(400, "Valid expected revisions are required"); }
        return json.writeValueAsString(bindings.checkForRuntime(ChatOrigin.from(context), criterionKey,
                new vip.mate.goal.service.GoalJsonBindingService.CheckRequest(revision, artifactId, generation)));
    }

}
