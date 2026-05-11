package vip.mate.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.workspace.document.WorkspaceFileService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 模板服务
 * <p>
 * 扫描 classpath:templates/*.json 下的模板文件，
 * 支持列出模板和应用模板创建 Agent。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final AgentService agentService;
    private final WorkspaceFileService workspaceFileService;
    private final ObjectMapper objectMapper;
    private final AgentBindingService agentBindingService;
    private final SkillMapper skillMapper;
    private final AvailableToolService availableToolService;

    /**
     * 列出所有可用模板
     */
    public List<TemplateDTO> listTemplates() {
        List<TemplateDTO> templates = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources("classpath:templates/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    TemplateDTO dto = objectMapper.readValue(is, TemplateDTO.class);
                    templates.add(dto);
                } catch (IOException e) {
                    log.warn("Failed to parse template file: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan template files", e);
        }

        templates.sort(Comparator.comparing(TemplateDTO::getId));
        return templates;
    }

    /**
     * Backwards-compatible overload that defaults to the template's English
     * display strings (existing callers without locale context).
     */
    @Transactional
    public AgentEntity applyTemplate(String templateId, Long workspaceId, Long creatorUserId) {
        return applyTemplate(templateId, workspaceId, creatorUserId, null);
    }

    /**
     * Apply a template, picking name/description in the caller's preferred
     * language so the resulting agent reads natively in their locale. Falls
     * back to the template's primary (English) fields when the localized
     * variant is missing or no locale was supplied.
     *
     * @param templateId      template ID
     * @param workspaceId     target workspace ID (from X-Workspace-Id header)
     * @param creatorUserId   current user ID (creator attribution)
     * @param acceptLanguage  raw Accept-Language header; null/blank → English
     */
    @Transactional
    public AgentEntity applyTemplate(String templateId, Long workspaceId, Long creatorUserId, String acceptLanguage) {
        TemplateDTO template = listTemplates().stream()
                .filter(t -> t.getId().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new MateClawException("err.agent.template_not_found", "模板不存在: " + templateId));

        boolean preferZh = isChineseLocale(acceptLanguage);
        String displayName = preferZh && template.getNameZh() != null && !template.getNameZh().isBlank()
                ? template.getNameZh()
                : template.getName();
        String displayDesc = preferZh && template.getDescriptionZh() != null && !template.getDescriptionZh().isBlank()
                ? template.getDescriptionZh()
                : template.getDescription();

        // 1. Create the Agent. workspaceId/creatorUserId are passed in
        // explicitly so the DB default does not silently fall back to 1.
        AgentEntity agent = new AgentEntity();
        agent.setName(displayName);
        agent.setDescription(displayDesc);
        agent.setAgentType(template.getAgentType());
        agent.setIcon(template.getIcon());
        agent.setTags(template.getTags());
        agent.setMaxIterations(template.getMaxIterations());
        if (template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank()) {
            agent.setSystemPrompt(template.getSystemPrompt());
        }
        agent.setWorkspaceId(workspaceId);
        agent.setCreatorUserId(creatorUserId);
        AgentEntity created = agentService.createAgent(agent);

        // 2. 创建工作区文件
        if (template.getWorkspaceFiles() != null && !template.getWorkspaceFiles().isEmpty()) {
            for (TemplateDTO.WorkspaceFileTemplate wf : template.getWorkspaceFiles()) {
                workspaceFileService.saveFile(created.getId(), wf.getFilename(), wf.getContent());
            }

            // 3. 启用指定文件并按 sortOrder 排序（setPromptFiles 用列表索引作为排序值）
            List<String> enabledFilenames = template.getWorkspaceFiles().stream()
                    .filter(wf -> Boolean.TRUE.equals(wf.getEnabled()))
                    .sorted((a, b) -> {
                        int sa = a.getSortOrder() != null ? a.getSortOrder() : 0;
                        int sb = b.getSortOrder() != null ? b.getSortOrder() : 0;
                        return Integer.compare(sa, sb);
                    })
                    .map(TemplateDTO.WorkspaceFileTemplate::getFilename)
                    .toList();

            if (!enabledFilenames.isEmpty()) {
                workspaceFileService.setPromptFiles(created.getId(), enabledFilenames);
            }
        }

        // 4. Pre-bind skills the template declares so a hired agent is
        // usable out of the box ("数据分析师" already knows SQL, "代码审查员"
        // already has the test-driven-development playbook). Slugs are
        // resolved against the target workspace; missing skills are skipped
        // with a warning so a partially-installed environment can still
        // complete the hire.
        applyDefaultSkillBindings(template, created, workspaceId);

        // 5. Pre-bind any standalone tools the template wants. Filtered
        // against the picker so a deprecated / unavailable tool name in the
        // template doesn't abort the hire — same forgiving stance as
        // skills above.
        applyDefaultToolBindings(template, created);

        return created;
    }

    /**
     * Resolve {@link TemplateDTO#getDefaultSkillSlugs()} to skill ids inside
     * {@code workspaceId} and pre-bind them on the freshly-created agent.
     * Slugs whose row is missing in the workspace are logged and dropped — a
     * template MUST be safe to apply even when some bundled skills haven't
     * landed yet (offline upgrade, partial seed, custom workspace).
     */
    private void applyDefaultSkillBindings(TemplateDTO template, AgentEntity created, Long workspaceId) {
        List<String> slugs = template.getDefaultSkillSlugs();
        if (slugs == null || slugs.isEmpty()) return;

        List<Long> resolvedIds = new ArrayList<>();
        for (String slug : slugs) {
            if (slug == null || slug.isBlank()) continue;
            SkillEntity skill = skillMapper.selectOne(new LambdaQueryWrapper<SkillEntity>()
                    .eq(SkillEntity::getName, slug.trim())
                    .eq(SkillEntity::getWorkspaceId, workspaceId));
            if (skill == null) {
                log.warn("[Template] template {} requested skill slug '{}' not found in workspace {}; skipping",
                        template.getId(), slug, workspaceId);
                continue;
            }
            resolvedIds.add(skill.getId());
        }
        if (resolvedIds.isEmpty()) return;

        agentBindingService.setSkillBindings(created.getId(), resolvedIds);
        log.info("[Template] template {} pre-bound {} skill(s) on agent {}",
                template.getId(), resolvedIds.size(), created.getId());
    }

    /**
     * Pre-filter the template's tool names through the picker so
     * {@link AgentBindingService#setToolBindings} sees only resolvable names
     * — its own validation would otherwise abort the call on the first
     * unknown name and leave the agent with no tool bindings at all.
     */
    private void applyDefaultToolBindings(TemplateDTO template, AgentEntity created) {
        List<String> names = template.getDefaultToolNames();
        if (names == null || names.isEmpty()) return;

        Set<String> bindable;
        try {
            bindable = availableToolService.listAvailable().stream()
                    .filter(AvailableToolDTO::isAvailable)
                    .map(AvailableToolDTO::getName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[Template] picker unavailable during template {} apply; skipping tool pre-bind: {}",
                    template.getId(), e.getMessage());
            return;
        }

        List<String> filtered = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String trimmed = name.trim();
            if (bindable.contains(trimmed)) {
                filtered.add(trimmed);
            } else {
                log.warn("[Template] template {} requested tool '{}' not currently bindable; skipping",
                        template.getId(), trimmed);
            }
        }
        if (filtered.isEmpty()) return;

        agentBindingService.setToolBindings(created.getId(), filtered);
        log.info("[Template] template {} pre-bound {} tool(s) on agent {}",
                template.getId(), filtered.size(), created.getId());
    }

    /**
     * True when the raw Accept-Language header best-matches a Chinese locale.
     * Implementation is intentionally simple — we only need to disambiguate
     * "Chinese vs not" for picking nameZh / descriptionZh.
     */
    private boolean isChineseLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return false;
        String first = acceptLanguage.split(",")[0].trim().toLowerCase();
        return first.startsWith("zh");
    }
}
