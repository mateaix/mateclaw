package vip.mate.tool.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import java.util.List;

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
        return toolRegistry.listToolEntities();
    }

    public List<ToolEntity> listEnabledTools() {
        return toolRegistry.listEnabledToolEntities();
    }

    public ToolEntity getTool(Long id) {
        ToolEntity tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new MateClawException("err.tool.not_found", "工具不存在: " + id);
        }
        return tool;
    }

    public ToolEntity createTool(ToolEntity tool) {
        tool.setBuiltin(false);
        if (tool.getEnabled() == null) {
            tool.setEnabled(true);
        }
        toolMapper.insert(tool);
        return tool;
    }

    public ToolEntity updateTool(ToolEntity tool) {
        ToolEntity existing = getTool(tool.getId());
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            existing.setEnabled(tool.getEnabled());
            toolMapper.updateById(existing);
            return existing;
        }
        toolMapper.updateById(tool);
        return tool;
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
        return tool;
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
        return tool;
    }
}
