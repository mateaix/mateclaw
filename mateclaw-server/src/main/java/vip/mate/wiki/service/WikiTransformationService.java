package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.repository.WikiTransformationMapper;
import vip.mate.wiki.repository.WikiTransformationRunMapper;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CRUD + lookups for wiki transformation templates and their execution
 * history. Pure persistence — the LLM call lives in
 * {@link WikiTransformationExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiTransformationService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    private final WikiTransformationMapper transformationMapper;
    private final WikiTransformationRunMapper runMapper;

    /** Templates visible to a KB: pinned to this KB plus workspace-wide ones (kb_id NULL). */
    public List<WikiTransformationEntity> listForKb(Long kbId, Long workspaceId) {
        if (kbId == null) {
            return List.of();
        }
        return transformationMapper.selectList(
                new LambdaQueryWrapper<WikiTransformationEntity>()
                        .and(w -> w.eq(WikiTransformationEntity::getKbId, kbId)
                                .or(g -> g.isNull(WikiTransformationEntity::getKbId)
                                        .eq(WikiTransformationEntity::getWorkspaceId, workspaceId)))
                        .orderByDesc(WikiTransformationEntity::getUpdateTime));
    }

    public List<WikiTransformationEntity> listByWorkspace(Long workspaceId) {
        return transformationMapper.selectList(
                new LambdaQueryWrapper<WikiTransformationEntity>()
                        .eq(WikiTransformationEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WikiTransformationEntity::getUpdateTime));
    }

    public WikiTransformationEntity getById(Long id) {
        return transformationMapper.selectById(id);
    }

    public Optional<WikiTransformationEntity> findByName(Long kbId, Long workspaceId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        // Prefer the KB-pinned record over a workspace-wide one of the same name.
        WikiTransformationEntity pinned = transformationMapper.selectOne(
                new LambdaQueryWrapper<WikiTransformationEntity>()
                        .eq(WikiTransformationEntity::getKbId, kbId)
                        .eq(WikiTransformationEntity::getName, name)
                        .last("LIMIT 1"));
        if (pinned != null) return Optional.of(pinned);
        WikiTransformationEntity global = transformationMapper.selectOne(
                new LambdaQueryWrapper<WikiTransformationEntity>()
                        .isNull(WikiTransformationEntity::getKbId)
                        .eq(WikiTransformationEntity::getWorkspaceId, workspaceId)
                        .eq(WikiTransformationEntity::getName, name)
                        .last("LIMIT 1"));
        return Optional.ofNullable(global);
    }

    /** Default-apply templates that should run for a raw material in {@code kbId}. */
    public List<WikiTransformationEntity> listApplyDefaultsForKb(Long kbId, Long workspaceId) {
        return listForKb(kbId, workspaceId).stream()
                .filter(t -> Boolean.TRUE.equals(t.getApplyDefault()))
                .filter(t -> !Boolean.FALSE.equals(t.getEnabled()))
                .toList();
    }

    @Transactional
    public WikiTransformationEntity create(WikiTransformationEntity input) {
        validateName(input.getName());
        if (input.getTitle() == null || input.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (input.getPromptTemplate() == null || input.getPromptTemplate().isBlank()) {
            throw new IllegalArgumentException("promptTemplate is required");
        }
        Long workspaceId = input.getWorkspaceId() == null ? 1L : input.getWorkspaceId();

        // Enforce uniqueness on (kbId, name) — including the NULL-kbId case
        // where MySQL would otherwise allow duplicates.
        findByExactScopeAndName(input.getKbId(), workspaceId, input.getName())
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Transformation already exists: " + input.getName());
                });

        WikiTransformationEntity entity = new WikiTransformationEntity();
        entity.setKbId(input.getKbId());
        entity.setWorkspaceId(workspaceId);
        entity.setName(input.getName());
        entity.setTitle(input.getTitle());
        entity.setDescription(input.getDescription());
        entity.setPromptTemplate(input.getPromptTemplate());
        entity.setApplyDefault(Boolean.TRUE.equals(input.getApplyDefault()));
        entity.setEnabled(input.getEnabled() == null ? Boolean.TRUE : input.getEnabled());
        // Treat negative values as the "clear / use default" sentinel so the
        // create and update paths accept the same payload from the UI.
        entity.setModelId(input.getModelId() != null && input.getModelId() < 0 ? null : input.getModelId());
        entity.setOutputTarget(normalizeOutputTarget(input.getOutputTarget()));
        entity.setOutputFormat(normalizeOutputFormat(input.getOutputFormat()));
        entity.setOutputSchema(sanitizeOutputSchema(input.getOutputSchema()));
        transformationMapper.insert(entity);
        log.info("[WikiTransformation] created id={} name={} kbId={}",
                entity.getId(), entity.getName(), entity.getKbId());
        return entity;
    }

    @Transactional
    public WikiTransformationEntity update(Long id, WikiTransformationEntity patch) {
        WikiTransformationEntity entity = transformationMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Transformation not found: " + id);
        }
        if (patch.getTitle() != null) entity.setTitle(patch.getTitle());
        if (patch.getDescription() != null) entity.setDescription(patch.getDescription());
        if (patch.getPromptTemplate() != null) entity.setPromptTemplate(patch.getPromptTemplate());
        if (patch.getApplyDefault() != null) entity.setApplyDefault(patch.getApplyDefault());
        if (patch.getEnabled() != null) entity.setEnabled(patch.getEnabled());
        // modelId is allowed to be cleared via explicit -1 sentinel handled by controller;
        // here we only honour non-null assignments.
        if (patch.getModelId() != null) {
            entity.setModelId(patch.getModelId() < 0 ? null : patch.getModelId());
        }
        if (patch.getOutputTarget() != null) {
            entity.setOutputTarget(normalizeOutputTarget(patch.getOutputTarget()));
        }
        if (patch.getOutputFormat() != null) {
            entity.setOutputFormat(normalizeOutputFormat(patch.getOutputFormat()));
        }
        if (patch.getOutputSchema() != null) {
            // Empty string clears the schema; non-blank gets stored after a parse check.
            entity.setOutputSchema(sanitizeOutputSchema(patch.getOutputSchema()));
        }
        transformationMapper.updateById(entity);
        return entity;
    }

    /** Whitelist incoming outputTarget; unknown / null = "none". */
    private static String normalizeOutputTarget(String raw) {
        if (raw == null) return "none";
        String trimmed = raw.trim().toLowerCase();
        return switch (trimmed) {
            case "page" -> "page";
            default -> "none";
        };
    }

    /** Whitelist incoming outputFormat; unknown / null = "markdown". */
    private static String normalizeOutputFormat(String raw) {
        if (raw == null) return "markdown";
        String trimmed = raw.trim().toLowerCase();
        return switch (trimmed) {
            case "json" -> "json";
            default -> "markdown";
        };
    }

    /**
     * Sanitises the user-supplied JSON Schema text. Empty / blank values
     * clear the column. Non-parseable values are rejected at the API
     * boundary so the executor doesn't have to defend against garbage
     * stored on the template.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper SCHEMA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String sanitizeOutputSchema(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        try {
            SCHEMA_MAPPER.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("output_schema is not valid JSON: " + e.getMessage());
        }
        return trimmed;
    }

    @Transactional
    public void delete(Long id) {
        transformationMapper.deleteById(id);
    }

    // ==================== Runs ====================

    public WikiTransformationRunEntity getRun(Long runId) {
        return runMapper.selectById(runId);
    }

    public List<WikiTransformationRunEntity> listRunsByRaw(Long rawId, int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<WikiTransformationRunEntity>()
                        .eq(WikiTransformationRunEntity::getRawId, rawId)
                        .orderByDesc(WikiTransformationRunEntity::getCreateTime)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    public List<WikiTransformationRunEntity> listRunsByKb(Long kbId, int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<WikiTransformationRunEntity>()
                        .eq(WikiTransformationRunEntity::getKbId, kbId)
                        .orderByDesc(WikiTransformationRunEntity::getCreateTime)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    public List<WikiTransformationRunEntity> listRunsByTransformation(Long transformationId, int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<WikiTransformationRunEntity>()
                        .eq(WikiTransformationRunEntity::getTransformationId, transformationId)
                        .orderByDesc(WikiTransformationRunEntity::getCreateTime)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public WikiTransformationRunEntity insertRun(WikiTransformationRunEntity run) {
        runMapper.insert(run);
        return run;
    }

    @Transactional
    public void updateRun(WikiTransformationRunEntity run) {
        runMapper.updateById(run);
    }

    @Transactional
    public void deleteRun(Long runId) {
        runMapper.deleteById(runId);
    }

    // ==================== helpers ====================

    private Optional<WikiTransformationEntity> findByExactScopeAndName(Long kbId, Long workspaceId, String name) {
        LambdaQueryWrapper<WikiTransformationEntity> q = new LambdaQueryWrapper<>();
        if (kbId == null) {
            q.isNull(WikiTransformationEntity::getKbId)
             .eq(WikiTransformationEntity::getWorkspaceId, workspaceId);
        } else {
            q.eq(WikiTransformationEntity::getKbId, kbId);
        }
        q.eq(WikiTransformationEntity::getName, name).last("LIMIT 1");
        return Optional.ofNullable(transformationMapper.selectOne(q));
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must be 3-64 chars, lowercase letters / digits / hyphens (start and end alphanumeric)");
        }
    }
}
