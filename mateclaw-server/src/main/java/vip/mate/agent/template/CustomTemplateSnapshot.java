package vip.mate.agent.template;

import vip.mate.agent.model.AgentEntity;

import java.util.List;

/**
 * Deliberate field allowlist: never serialize an AgentEntity or credential-bearing binding config.
 */
public record CustomTemplateSnapshot(
        int schemaVersion,
        Metadata metadata,
        List<Skill> skills,
        List<Tool> tools,
        List<KnowledgeBase> knowledgeBases,
        List<Provider> providers,
        List<Document> documents) {
    public static final int VERSION = 1;

    public record Metadata(
            String description,
            String agentType,
            String runtimeType,
            String systemPrompt,
            String modelName,
            Integer maxIterations,
            Boolean enabled,
            String icon,
            String tags,
            String defaultThinkingLevel,
            Long primaryKbId,
            Boolean skillsDisabled,
            Boolean toolsDisabled,
            Boolean wikiDisabled) {
        static Metadata from(AgentEntity a) {
            return new Metadata(
                    a.getDescription(),
                    a.getAgentType(),
                    "native",
                    a.getSystemPrompt(),
                    a.getModelName(),
                    a.getMaxIterations(),
                    a.getEnabled(),
                    a.getIcon(),
                    a.getTags(),
                    a.getDefaultThinkingLevel(),
                    a.getPrimaryKbId(),
                    a.getSkillsDisabled(),
                    a.getToolsDisabled(),
                    a.getWikiDisabled());
        }

        AgentEntity employee(String name, Long workspaceId, Long creatorUserId) {
            AgentEntity a = new AgentEntity();
            a.setName(name);
            a.setWorkspaceId(workspaceId);
            a.setCreatorUserId(creatorUserId);
            a.setDescription(description);
            a.setAgentType(agentType);
            a.setRuntimeType(runtimeType);
            a.setSystemPrompt(systemPrompt);
            a.setModelName(modelName);
            a.setMaxIterations(maxIterations);
            a.setEnabled(enabled);
            a.setIcon(icon);
            a.setTags(tags);
            a.setDefaultThinkingLevel(defaultThinkingLevel);
            a.setPrimaryKbId(primaryKbId);
            a.setSkillsDisabled(skillsDisabled);
            a.setToolsDisabled(toolsDisabled);
            a.setWikiDisabled(wikiDisabled);
            return a;
        }
    }

    public record Skill(Long skillId, Boolean enabled) {}

    public record Tool(String toolName, Boolean enabled) {}

    public record KnowledgeBase(Long kbId, Boolean enabled) {}

    public record Provider(String providerId, Long modelId, Integer sortOrder, Boolean enabled) {}

    public record Document(String filename, String content, Boolean enabled, Integer sortOrder) {}
}
