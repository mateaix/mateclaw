package vip.mate.tool.guard.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则注册表
 * <p>
 * 统一管理内置规则 + DB 自定义规则。
 * 启动时加载，支持 reload() 热重载。
 */
@Slf4j
@Component
@Order(115) // 在 ToolGuardRuleSeedService(110) 之后，确保种子规则已写入
@RequiredArgsConstructor
public class ToolGuardRuleRegistry implements ApplicationRunner {

    private final ToolGuardRuleMapper ruleMapper;

    private volatile List<ToolGuardRuleEntity> allRules = List.of();
    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /**
     * 重新从 DB 加载所有规则
     */
    public void reload() {
        try {
            List<ToolGuardRuleEntity> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getEnabled, true)
                            .orderByDesc(ToolGuardRuleEntity::getPriority)
            );
            this.allRules = List.copyOf(rules);
            log.info("[ToolGuardRuleRegistry] Loaded {} enabled rules", rules.size());
        } catch (Exception e) {
            log.warn("[ToolGuardRuleRegistry] Failed to load rules (table may not exist): {}", e.getMessage());
            this.allRules = List.of();
        }
    }

    /**
     * 获取适用于指定工具的规则
     */
    public List<ToolGuardRuleEntity> getRulesForTool(String toolName) {
        return allRules.stream()
                .filter(r -> r.getToolName() == null || r.getToolName().isEmpty()
                        || r.getToolName().equals(toolName))
                .collect(Collectors.toList());
    }

    /**
     * 按 category 取所有已启用规则（不限工具）。
     * 用于 alwaysRun 类的横切 Guardian（凭据扫描、PII 扫描等）。
     */
    public List<ToolGuardRuleEntity> getRulesByCategory(String category) {
        if (category == null || category.isEmpty()) {
            return List.of();
        }
        return allRules.stream()
                .filter(r -> category.equals(r.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已启用规则
     */
    public List<ToolGuardRuleEntity> getAllEnabled() {
        return allRules;
    }

    /**
     * 获取编译后的正则模式
     */
    public Pattern getCompiledPattern(String regex) {
        return compiledPatterns.computeIfAbsent(regex,
                r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
    }

    /**
     * 获取编译后的排除模式
     */
    public Pattern getCompiledExcludePattern(String regex) {
        return compiledPatterns.computeIfAbsent("exclude:" + regex,
                r -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }
}
