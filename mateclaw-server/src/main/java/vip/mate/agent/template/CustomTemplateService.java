package vip.mate.agent.template;

import static vip.mate.agent.template.CustomTemplateSnapshot.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.i18n.I18nService;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.tool.mcp.runtime.McpWorkspaceAccess;
import vip.mate.tool.service.AvailableToolService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomTemplateService {
    public static final Set<String> SHARED_CONFIG_FILES =
            Set.of(
                    "AGENTS.md",
                    "SOUL.md",
                    "TOOLS.md",
                    "IDENTITY.md",
                    "HEARTBEAT.md",
                    "PROACTIVE.md");
    private final CustomTemplateRepository repository;
    private final AgentService agents;
    private final AvailableToolService availableTools;
    private final ObjectMapper json;
    private final I18nService i18n;
    private final McpSkillBridge mcpSkills;
    private final AcpSkillBridge acpSkills;
    private final McpWorkspaceAccess mcpAccess;

    public record SaveRequest(
            Long sourceAgentId, String name, String description, List<String> workspaceFileNames) {}

    public record ApplyRequest(String name) {}

    public record Summary(
            String id,
            String name,
            String description,
            String icon,
            String agentType,
            Long creatorUserId,
            LocalDateTime createTime,
            int schemaVersion,
            boolean canDelete) {
        public Summary withCanDelete(boolean allowed) {
            return new Summary(
                    id,
                    name,
                    description,
                    icon,
                    agentType,
                    creatorUserId,
                    createTime,
                    schemaVersion,
                    allowed);
        }
    }

    public List<Summary> list(Long workspaceId) {
        return repository.list(workspaceId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Summary save(Long workspaceId, Long creatorUserId, SaveRequest request) {
        if (request == null
                || request.sourceAgentId() == null
                || request.workspaceFileNames() == null)
            throw error("invalid", "Source employee and selected document list are required");
        String name = name(request.name(), 255);
        AgentEntity source = agents.getAgent(request.sourceAgentId());
        if (!Objects.equals(
                workspaceId, source.getWorkspaceId() == null ? 1L : source.getWorkspaceId()))
            throw wrongWorkspace();
        nativeOnly(source.getRuntimeType());
        if (repository.templateNameExists(workspaceId, name))
            throw error("duplicate_name", "A template with this name already exists");
        List<Document> documents = new ArrayList<>();
        for (String filename : new LinkedHashSet<>(request.workspaceFileNames())) {
            if (!SHARED_CONFIG_FILES.contains(filename))
                throw error(
                        "document_forbidden",
                        "Only selected shared configuration documents can be saved: " + filename);
            List<Document> matches = repository.documents(source.getId(), filename);
            if (matches.size() != 1)
                throw error(
                        "document_forbidden",
                        "Shared configuration document is missing or ambiguous: " + filename);
            documents.add(matches.getFirst());
        }
        CustomTemplateSnapshot snapshot =
                new CustomTemplateSnapshot(
                        VERSION,
                        Metadata.from(source),
                        repository.skills(source.getId()),
                        repository.tools(source.getId()),
                        repository.kbs(source.getId()),
                        repository.providers(source.getId()),
                        documents);
        Summary summary =
                new Summary(
                        UUID.randomUUID().toString(),
                        name,
                        request.description(),
                        source.getIcon(),
                        source.getAgentType(),
                        creatorUserId,
                        LocalDateTime.now(),
                        VERSION,
                        true);
        try {
            repository.insert(workspaceId, summary, json.writeValueAsString(snapshot));
        } catch (DuplicateKeyException e) {
            throw error("duplicate_name", "A template with this name already exists");
        } catch (JsonProcessingException e) {
            throw error("invalid", "Unable to serialize template snapshot");
        }
        return summary;
    }

    @Transactional
    public AgentEntity apply(
            Long workspaceId, Long creatorUserId, String id, ApplyRequest request) {
        require(workspaceId, id);
        String name = name(request == null ? null : request.name(), 128);
        if (repository.agentNameExists(workspaceId, name))
            throw error("duplicate_name", "An employee with this name already exists");
        CustomTemplateSnapshot snapshot;
        try {
            snapshot =
                    json.readValue(
                            repository.snapshot(workspaceId, id), CustomTemplateSnapshot.class);
        } catch (JsonProcessingException e) {
            throw error("invalid", "Invalid template snapshot");
        }
        if (snapshot.schemaVersion() != VERSION)
            throw error("version", "Unsupported template snapshot version");
        nativeOnly(snapshot.metadata().runtimeType());
        validateDependencies(workspaceId, snapshot);
        AgentEntity employee = snapshot.metadata().employee(name, workspaceId, creatorUserId);
        AgentEntity created = agents.createAgent(employee);
        repository.restore(created.getId(), snapshot);
        created.setEnabled(snapshot.metadata().enabled());
        return created;
    }

    private boolean skillAvailable(Long workspaceId, Long skillId) {
        if (McpSkillBridge.isVirtualMcpSkillId(skillId)) {
            return mcpAccess.canUse(McpSkillBridge.extractMcpServerId(skillId), workspaceId)
                    && SkillRuntimeService.passesActiveGate(mcpSkills.findResolvedById(skillId));
        }
        if (AcpSkillBridge.isVirtualAcpSkillId(skillId)) {
            var resolved = acpSkills.findResolvedById(skillId);
            return SkillRuntimeService.passesActiveGate(resolved)
                    && SkillRuntimeService.matchesWorkspace(resolved, workspaceId);
        }
        return repository.skillAvailable(workspaceId, skillId);
    }

    private void validateDependencies(Long workspaceId, CustomTemplateSnapshot s) {
        // Validate every configured row, including disabled rows; dropping one could turn an
        // explicit empty scope into global inheritance.
        for (Skill b : s.skills())
            if (!skillAvailable(workspaceId, b.skillId()))
                missing("skill " + b.skillId());
        for (KnowledgeBase b : s.knowledgeBases())
            if (!repository.kbAvailable(workspaceId, b.kbId()))
                missing("knowledge base " + b.kbId());
        if (s.metadata().primaryKbId() != null
                && !repository.kbAvailable(workspaceId, s.metadata().primaryKbId()))
            missing("primary knowledge base " + s.metadata().primaryKbId());
        if (s.metadata().modelName() != null
                && !s.metadata().modelName().isBlank()
                && !repository.modelAvailable(s.metadata().modelName()))
            missing("model " + s.metadata().modelName());
        for (Provider p : s.providers())
            if (!repository.providerAvailable(p)) missing("model provider " + p.providerId());
        if (!s.tools().isEmpty()) {
            Set<String> available =
                    availableTools.listAvailable(workspaceId).stream()
                            .filter(t -> t.isAvailable())
                            .map(t -> t.getName())
                            .collect(Collectors.toSet());
            for (Tool t : s.tools())
                if (!available.contains(t.toolName())) missing("tool " + t.toolName());
        }
    }

    @Transactional
    public void delete(Long workspaceId, Long userId, String id, boolean admin) {
        Summary template = require(workspaceId, id);
        if (!admin && !Objects.equals(userId, template.creatorUserId()))
            throw new MateClawException(
                    "err.custom_template.delete_forbidden",
                    403,
                    "Only the creator or a workspace administrator can delete this template");
        repository.delete(workspaceId, id);
    }

    private Summary require(Long workspaceId, String id) {
        Summary t = repository.find(workspaceId, id);
        if (t == null)
            throw new MateClawException(
                    "err.custom_template.not_found",
                    404,
                    "Template not found in the current workspace");
        return t;
    }

    private static String name(String value, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength)
            throw error(
                    "invalid", "Name is required and must be at most " + maxLength + " characters");
        return value.trim();
    }

    private static void nativeOnly(String runtime) {
        if (runtime != null && !runtime.isBlank() && !"native".equalsIgnoreCase(runtime.trim()))
            throw error(
                    "runtime_unsupported",
                    "Custom templates currently support native employees only; DSH runtime"
                            + " configuration cannot be safely copied");
    }

    private void missing(String dependency) {
        String message = i18n.msg("err.custom_template.dependency_missing", dependency);
        if (message == null || message.equals("err.custom_template.dependency_missing"))
            message =
                    "Template dependency is missing, disabled, or outside this workspace: "
                            + dependency;
        throw new MateClawException(400, message);
    }

    private static MateClawException error(String key, String message) {
        return new MateClawException("err.custom_template." + key, 400, message);
    }

    private static MateClawException wrongWorkspace() {
        return new MateClawException(
                "err.common.wrong_workspace",
                403,
                "Resource does not belong to the current workspace");
    }
}
