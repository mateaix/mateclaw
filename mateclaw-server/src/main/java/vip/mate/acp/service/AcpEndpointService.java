package vip.mate.acp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.acp.event.AcpEndpointChangedEvent;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.repository.AcpEndpointMapper;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RFC-090 Phase 7 — CRUD layer for {@link AcpEndpointEntity}.
 *
 * <p>Keeps three guarantees:
 * <ol>
 *   <li>Builtin rows ({@code builtin=true}) cannot be hard-deleted —
 *       the user can only disable them. Mirrors {@code SkillService}.</li>
 *   <li>Names are unique; {@code create} validates against the live
 *       (non-deleted) set.</li>
 *   <li>{@code argsJson} / {@code envJson} round-trip through Jackson
 *       so the controller can hand structured data to the UI without
 *       leaking string-encoded JSON.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpEndpointService {

    public static final int DEFAULT_PROMPT_TIMEOUT_SECONDS = 300;
    public static final int MAX_PROMPT_TIMEOUT_SECONDS = 3600;

    private final AcpEndpointMapper mapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public List<AcpEndpointEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<AcpEndpointEntity>()
                .orderByDesc(AcpEndpointEntity::getBuiltin)
                .orderByAsc(AcpEndpointEntity::getName));
    }

    /**
     * Subset of {@link #list()} that returns only enabled rows.
     * Used by {@code AcpSkillBridge} to enumerate virtual skill cards
     * (one per enabled endpoint).
     */
    public List<AcpEndpointEntity> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<AcpEndpointEntity>()
                .eq(AcpEndpointEntity::getEnabled, true)
                .orderByAsc(AcpEndpointEntity::getName));
    }

    public AcpEndpointEntity get(Long id) {
        AcpEndpointEntity ep = mapper.selectById(id);
        if (ep == null) throw new MateClawException("err.acp.endpoint_not_found",
                "ACP endpoint not found: " + id);
        return ep;
    }

    public AcpEndpointEntity findByName(String name) {
        return mapper.selectOne(new LambdaQueryWrapper<AcpEndpointEntity>()
                .eq(AcpEndpointEntity::getName, name));
    }

    public AcpEndpointEntity create(AcpEndpointEntity input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw new MateClawException("err.acp.name_required", "ACP endpoint name is required");
        }
        if (input.getCommand() == null || input.getCommand().isBlank()) {
            throw new MateClawException("err.acp.command_required", "ACP endpoint command is required");
        }
        if (findByName(input.getName()) != null) {
            throw new MateClawException("err.acp.name_exists",
                    "ACP endpoint name already exists: " + input.getName());
        }
        // User-created rows are never builtin; default-enable false so a
        // misconfigured row can't auto-spawn a process at startup.
        input.setBuiltin(false);
        if (input.getEnabled() == null) input.setEnabled(false);
        if (input.getTrusted() == null) input.setTrusted(true);
        if (input.getToolParseMode() == null || input.getToolParseMode().isBlank()) {
            input.setToolParseMode("call_title");
        }
        if (input.getStdioBufferLimitBytes() == null || input.getStdioBufferLimitBytes() <= 0) {
            input.setStdioBufferLimitBytes(50L * 1024L * 1024L);
        }
        input.setPromptTimeoutSeconds(normalizePromptTimeoutSeconds(input.getPromptTimeoutSeconds()));
        if (input.getWorkspaceId() == null) input.setWorkspaceId(1L);
        mapper.insert(input);
        log.info("Created ACP endpoint: {}", input.getName());
        publish(input, AcpEndpointChangedEvent.Type.CREATED);
        return input;
    }

    public AcpEndpointEntity update(Long id, AcpEndpointEntity patch) {
        AcpEndpointEntity existing = get(id);
        if (Boolean.TRUE.equals(existing.getBuiltin())
                && patch.getCommand() != null
                && !patch.getCommand().equals(existing.getCommand())) {
            throw new MateClawException("err.acp.builtin_command_locked",
                    "Builtin ACP endpoint command cannot be changed: " + existing.getName());
        }
        // Allow surgical updates: only fields the caller actually set.
        if (patch.getDisplayName() != null) existing.setDisplayName(patch.getDisplayName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getCommand() != null) existing.setCommand(patch.getCommand());
        if (patch.getArgsJson() != null) existing.setArgsJson(patch.getArgsJson());
        if (patch.getEnvJson() != null) existing.setEnvJson(patch.getEnvJson());
        if (patch.getToolParseMode() != null) existing.setToolParseMode(patch.getToolParseMode());
        if (patch.getTrusted() != null) existing.setTrusted(patch.getTrusted());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getStdioBufferLimitBytes() != null && patch.getStdioBufferLimitBytes() > 0) {
            existing.setStdioBufferLimitBytes(patch.getStdioBufferLimitBytes());
        }
        if (patch.getPromptTimeoutSeconds() != null) {
            existing.setPromptTimeoutSeconds(normalizePromptTimeoutSeconds(patch.getPromptTimeoutSeconds()));
        }
        mapper.updateById(existing);
        publish(existing, AcpEndpointChangedEvent.Type.UPDATED);
        return existing;
    }

    public void delete(Long id) {
        AcpEndpointEntity existing = get(id);
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new MateClawException("err.acp.builtin_readonly",
                    "Builtin ACP endpoint cannot be deleted: " + existing.getName());
        }
        mapper.deleteById(id);
        log.info("Deleted ACP endpoint: {}", existing.getName());
        publish(existing, AcpEndpointChangedEvent.Type.DELETED);
    }

    public AcpEndpointEntity toggle(Long id, boolean enabled) {
        AcpEndpointEntity existing = get(id);
        existing.setEnabled(enabled);
        mapper.updateById(existing);
        publish(existing, AcpEndpointChangedEvent.Type.TOGGLED);
        return existing;
    }

    private void publish(AcpEndpointEntity ep, AcpEndpointChangedEvent.Type type) {
        try {
            eventPublisher.publishEvent(new AcpEndpointChangedEvent(
                    ep.getId(), ep.getName(), type));
        } catch (Exception e) {
            // Listener failures must not break the CRUD path. The bridge
            // will resync on the next ApplicationReady tick anyway.
            log.warn("Failed to publish AcpEndpointChangedEvent for '{}': {}",
                    ep.getName(), e.getMessage());
        }
    }

    /** Persist a connection-test outcome on the row. */
    public void recordTestResult(Long id, String status, String error) {
        AcpEndpointEntity existing = mapper.selectById(id);
        if (existing == null) return;
        existing.setLastStatus(status);
        existing.setLastTestedAt(LocalDateTime.now());
        existing.setLastError(error);
        mapper.updateById(existing);
    }

    public List<String> parseArgs(AcpEndpointEntity ep) {
        return parseStringList(ep.getArgsJson());
    }

    public Map<String, String> parseEnv(AcpEndpointEntity ep) {
        if (ep.getEnvJson() == null || ep.getEnvJson().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(ep.getEnvJson(),
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse env_json for ACP endpoint '{}': {}",
                    ep.getName(), e.getMessage());
            return Map.of();
        }
    }

    public static int normalizePromptTimeoutSeconds(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return DEFAULT_PROMPT_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_PROMPT_TIMEOUT_SECONDS);
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse args_json: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
