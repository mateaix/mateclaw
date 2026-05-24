package vip.mate.tool.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolMapper toolMapper;
    private final ToolRegistry toolRegistry;

    public List<ToolEntity> listTools() {
        return enrichRuntimeNames(toolRegistry.listToolEntities());
    }

    public List<ToolEntity> listEnabledTools() {
        return enrichRuntimeNames(toolRegistry.listEnabledToolEntities());
    }

    public ToolEntity getTool(Long id) {
        ToolEntity tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new MateClawException("err.tool.not_found", "工具不存在: " + id);
        }
        return enrichRuntimeNames(tool);
    }

    public ToolEntity createTool(ToolEntity tool) {
        tool.setBuiltin(false);
        if (tool.getEnabled() == null) {
            tool.setEnabled(true);
        }
        toolMapper.insert(tool);
        return enrichRuntimeNames(tool);
    }

    public ToolEntity updateTool(ToolEntity tool) {
        ToolEntity existing = getTool(tool.getId());
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            existing.setEnabled(tool.getEnabled());
            toolMapper.updateById(existing);
            return enrichRuntimeNames(existing);
        }
        toolMapper.updateById(tool);
        return enrichRuntimeNames(tool);
    }

    public void deleteTool(Long id) {
        ToolEntity tool = getTool(id);
        if (Boolean.TRUE.equals(tool.getBuiltin())) {
            throw new MateClawException("err.tool.builtin_readonly", "内置工具不可删除");
        }
        toolMapper.deleteById(id);
    }

    public ToolEntity toggleTool(Long id, boolean enabled) {
        ToolEntity tool = getTool(id);
        tool.setEnabled(enabled);
        toolMapper.updateById(tool);
        return enrichRuntimeNames(tool);
    }

    /**
     * Set the disclosure tier ({@code core} / {@code extension}) of a builtin or
     * channel atomic tool. MCP / ACP / skill tools are tiered at their owning
     * source, not here — the controller rejects those before calling this.
     */
    public ToolEntity setDisclosureTier(Long id, String tier) {
        ToolEntity tool = getTool(id);
        tool.setDisclosureTier(vip.mate.tool.disclosure.DisclosureTier.fromToken(tier).token());
        toolMapper.updateById(tool);
        return enrichRuntimeNames(tool);
    }

    private List<ToolEntity> enrichRuntimeNames(List<ToolEntity> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools;
        }
        vip.mate.agent.AgentToolSet set;
        try {
            set = toolRegistry.getAllToolBeanSetForAdmin();
        } catch (Exception e) {
            log.debug("Unable to enrich tool runtime names: {}", e.getMessage());
            return tools;
        }
        for (ToolEntity tool : tools) {
            enrichRuntimeNames(tool, set);
        }
        return tools;
    }

    private ToolEntity enrichRuntimeNames(ToolEntity tool) {
        if (tool == null) {
            return null;
        }
        try {
            enrichRuntimeNames(tool, toolRegistry.getAllToolBeanSetForAdmin());
        } catch (Exception e) {
            log.debug("Unable to enrich tool runtime names for {}: {}", tool.getName(), e.getMessage());
        }
        return tool;
    }

    private static void enrichRuntimeNames(ToolEntity tool, vip.mate.agent.AgentToolSet set) {
        if (tool == null || set == null) {
            return;
        }
        Set<String> aliases = new LinkedHashSet<>();
        if (tool.getName() != null && !tool.getName().isBlank()) {
            aliases.add(tool.getName());
        }
        if (tool.getBeanName() != null && !tool.getBeanName().isBlank()) {
            aliases.add(tool.getBeanName());
        }
        Set<String> names = set.functionNamesFor(aliases);
        if (!names.isEmpty()) {
            tool.setRuntimeNames(List.copyOf(names));
        }
    }
}
