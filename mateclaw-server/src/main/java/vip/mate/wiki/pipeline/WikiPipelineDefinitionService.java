package vip.mate.wiki.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.repository.WikiPipelineDefinitionMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CRUD + YAML/JSON parsing for user-defined pipelines. A definition's config
 * (YAML or JSON) carries {@code name}, {@code owner_agent}, a {@code trigger}
 * object and a {@code steps} array; this service parses it into the persisted
 * entity (trigger / steps stored as JSON).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiPipelineDefinitionService {

    private static final java.util.Set<String> KNOWN_EXECUTORS = java.util.Set.of("llm", "skill", "python");
    private static final java.util.Set<String> KNOWN_TRIGGERS =
            java.util.Set.of("page_type_count", "page_created", "stale_marked");

    private final WikiPipelineDefinitionMapper definitionMapper;
    private final ObjectMapper objectMapper;

    public WikiPipelineDefinitionService(WikiPipelineDefinitionMapper definitionMapper, ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.objectMapper = objectMapper;
    }

    public List<WikiPipelineDefinitionEntity> list(Long kbId) {
        return definitionMapper.selectList(new LambdaQueryWrapper<WikiPipelineDefinitionEntity>()
                .eq(WikiPipelineDefinitionEntity::getKbId, kbId)
                .orderByDesc(WikiPipelineDefinitionEntity::getCreateTime));
    }

    public WikiPipelineDefinitionEntity get(Long id) {
        return definitionMapper.selectById(id);
    }

    public void delete(Long id) {
        definitionMapper.deleteById(id);
    }

    /**
     * Parse a YAML/JSON pipeline config and upsert it (by kb + name). Throws
     * {@link IllegalArgumentException} on a structural problem.
     */
    @SuppressWarnings("unchecked")
    public WikiPipelineDefinitionEntity saveFromConfig(Long kbId, String config, boolean yaml) {
        Map<String, Object> root = parse(config, yaml);
        List<String> issues = validateParsed(root);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("Invalid pipeline config: " + String.join("; ", issues));
        }
        String name = String.valueOf(root.get("name"));
        Object owner = root.get("owner_agent");
        Map<String, Object> trigger = (Map<String, Object>) root.get("trigger");
        Object steps = root.get("steps");

        WikiPipelineDefinitionEntity entity = definitionMapper.selectOne(
                new LambdaQueryWrapper<WikiPipelineDefinitionEntity>()
                        .eq(WikiPipelineDefinitionEntity::getKbId, kbId)
                        .eq(WikiPipelineDefinitionEntity::getName, name)
                        .last("LIMIT 1"));
        boolean isNew = entity == null;
        if (isNew) {
            entity = new WikiPipelineDefinitionEntity();
            entity.setKbId(kbId);
            entity.setName(name);
            entity.setEnabled(1);
        }
        entity.setOwnerAgentId(Long.valueOf(String.valueOf(owner)));
        entity.setTriggerType(String.valueOf(trigger.get("type")));
        try {
            entity.setTriggerConfigJson(objectMapper.writeValueAsString(trigger));
            entity.setStepsJson(objectMapper.writeValueAsString(steps));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize pipeline config: " + e.getMessage());
        }
        Object dedup = trigger.get("dedup_window_seconds");
        entity.setDedupWindowSeconds(dedup instanceof Number ? ((Number) dedup).intValue() : 0);

        if (isNew) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
        }
        return entity;
    }

    /** Validate a config without saving; returns human-readable issues (empty = valid). */
    public List<String> validateConfig(String config, boolean yaml) {
        try {
            return validateParsed(parse(config, yaml));
        } catch (Exception e) {
            return List.of("Unparseable config: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String config, boolean yaml) {
        if (config == null || config.isBlank()) {
            throw new IllegalArgumentException("config is empty");
        }
        try {
            if (yaml) {
                Object loaded = new org.yaml.snakeyaml.Yaml().load(config);
                if (!(loaded instanceof Map)) {
                    throw new IllegalArgumentException("YAML root must be a mapping");
                }
                return (Map<String, Object>) loaded;
            }
            return objectMapper.readValue(config, Map.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> validateParsed(Map<String, Object> root) {
        List<String> issues = new ArrayList<>();
        if (root.get("name") == null || String.valueOf(root.get("name")).isBlank()) {
            issues.add("missing 'name'");
        }
        if (root.get("owner_agent") == null) {
            issues.add("missing 'owner_agent' (steps run under this agent)");
        }
        Object trig = root.get("trigger");
        if (!(trig instanceof Map)) {
            issues.add("missing 'trigger' object");
        } else {
            String type = String.valueOf(((Map<String, Object>) trig).get("type"));
            if (!KNOWN_TRIGGERS.contains(type)) {
                issues.add("unknown trigger type '" + type + "' (expected one of " + KNOWN_TRIGGERS + ")");
            }
        }
        Object steps = root.get("steps");
        if (!(steps instanceof List) || ((List<?>) steps).isEmpty()) {
            issues.add("'steps' must be a non-empty array");
        } else {
            for (Object s : (List<Object>) steps) {
                if (!(s instanceof Map)) { issues.add("each step must be an object"); continue; }
                String ex = String.valueOf(((Map<String, Object>) s).get("executor"));
                if (!KNOWN_EXECUTORS.contains(ex)) {
                    issues.add("step executor '" + ex + "' unknown (expected " + KNOWN_EXECUTORS + ")");
                }
                if ("python".equals(ex)) {
                    issues.add("python executor needs a sandbox and is not enabled in this build");
                }
            }
        }
        return issues;
    }
}
