package vip.mate.team.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.repository.AgentTeamMapper;

import java.util.Set;

/** Mapper-only role policy shared by tool scoping and graph construction. */
@Component
@RequiredArgsConstructor
public class TeamCoordinatorPolicy {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AgentTeamMapper teamMapper;

    public static final Set<String> TOOLS = Set.of(
            "team_tasks", "getCurrentDate", "getCurrentDateTime", "getCurrentTime",
            "remember", "remember_structured", "recall_structured", "forget_structured",
            "list_workspace_memory_files", "read_workspace_memory_file", "write_workspace_memory_file",
            "edit_workspace_memory_file", "search_workspace_memory",
            "tool_search", "tool_describe", "tool_call", "enable_tool");

    /** Saved coordinator scope survives a paused team; pausing must never restore execution tools. */
    public boolean isCoordinator(Long agentId) {
        if (agentId == null) return false;
        AgentTeamEntity team = teamMapper.selectOne(Wrappers.<AgentTeamEntity>lambdaQuery()
                .eq(AgentTeamEntity::getLeadAgentId, agentId)
                .last("LIMIT 1"));
        return team != null && agentId.equals(team.getLeadAgentId()) && enabled(team.getSettings());
    }

    public void requireSupportedRuntime(AgentEntity agent) {
        if (agent != null && isCoordinator(agent.getId())) requireNative(agent.getRuntimeType());
    }

    public static void requireNative(String runtimeType) {
        if (runtimeType != null && !runtimeType.isBlank() && !"native".equalsIgnoreCase(runtimeType.trim()))
            throw new MateClawException("err.team.coordinator_native_required", 400, "Coordinator mode requires a native-runtime lead");
    }

    /** Old teams accepted arbitrary settings strings; only an explicit valid boolean opts in. */
    public static boolean enabled(String settings) {
        try {
            return object(settings).path("coordinatorMode").asBoolean(false);
        } catch (MateClawException invalidLegacySettings) {
            return false;
        }
    }

    public static String mergeSettings(String existing, String patch) {
        if (patch == null || patch.isBlank()) throw invalidSettings();
        ObjectNode merged = object(existing);
        merged.setAll(object(patch));
        return merged.toString();
    }

    private static MateClawException invalidSettings() {
        return new MateClawException("err.team.coordinator_settings_invalid", 400,
                "Team settings must be a valid JSON object; coordinatorMode must be boolean. Existing invalid settings must be repaired before merging.");
    }

    private static ObjectNode object(String settings) {
        if (settings == null || settings.isBlank()) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(settings);
            if (!(node instanceof ObjectNode object)) throw invalidSettings();
            JsonNode mode = object.get("coordinatorMode");
            if (mode != null && !mode.isBoolean()) throw invalidSettings();
            return object;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalidSettings();
        }
    }
}
