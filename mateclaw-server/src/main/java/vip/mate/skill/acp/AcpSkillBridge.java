package vip.mate.skill.acp;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import vip.mate.acp.event.AcpEndpointChangedEvent;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.service.AcpDelegationService;
import vip.mate.acp.service.AcpEndpointService;
import vip.mate.skill.knowledge.SkillScopedToolCallback;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFC-090 §3.2 / §14.4 (parallel) — ACP endpoint → virtual skill bridge.
 *
 * <p>Mirrors {@link vip.mate.skill.mcp.McpSkillBridge}: every enabled
 * row in {@code mate_acp_endpoint} is automatically surfaced as a
 * virtual {@link SkillEntity} + {@link ResolvedSkill}, and a wrapper
 * tool ({@code acp_<slug>_prompt}) is registered with
 * {@link ToolRegistry} so any agent can delegate to that endpoint
 * without manual binding.
 *
 * <p>This solves the "ACP configured as skill cannot be called"
 * usability bug while keeping MateClaw's skill-card affordance for
 * endpoint discovery: the user manages endpoints in Settings ▸ ACP
 * Endpoints, and a card automatically appears on the Skills page —
 * no per-endpoint SKILL.md authoring required.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link ApplicationReadyEvent} — initial wrapper registration
 *       for all enabled endpoints.</li>
 *   <li>{@link AcpEndpointChangedEvent} — re-sync registrations on
 *       create / update / toggle / delete.</li>
 *   <li>{@code list/list-status} entry points rebuild the virtual
 *       SkillEntity / ResolvedSkill snapshots on demand so the Skills
 *       page always shows current state.</li>
 * </ul>
 *
 * <p>ID namespace: virtual ACP ids set both top bits of a {@code long}
 * (bit 63 + bit 62) so they sit in a different type-tag than MCP
 * (which sets only bit 63 — see
 * {@link vip.mate.skill.mcp.McpSkillBridge}). The bottom 62 bits carry
 * the underlying endpointId. The earlier {@code 8e18 + endpointId}
 * addition scheme broke once Snowflake-issued endpoint ids crossed
 * the {@code 1e17} bound, so the bit-tagged layout replaces it.
 *
 * <p>{@code VIRTUAL_ID_BASE + smallId} still equals
 * {@code VIRTUAL_ID_BASE | smallId} for any {@code smallId < 2^62}, so
 * test fixtures that build virtual ids by addition continue to work.
 */
@Slf4j
@Service
public class AcpSkillBridge {

    /** Type tag for ACP virtual ids: bits 63 + 62 set. */
    public static final long VIRTUAL_ID_BASE = 0xC000000000000000L;
    /**
     * @deprecated The bound is implicit in the bit-tag layout — any id
     *     whose top two bits are both set is an ACP virtual id. Kept
     *     for source compatibility with earlier callers.
     */
    @Deprecated
    public static final long VIRTUAL_ID_BOUND = -1L; // 0xFFFFFFFFFFFFFFFFL

    /** Selects the top-two type-tag bits. */
    private static final long TAG_MASK = 0xC000000000000000L;
    /** Selects the bottom 62 bits that carry the original endpoint id. */
    private static final long ID_MASK = 0x3FFFFFFFFFFFFFFFL;

    private final AcpEndpointService endpointService;
    private final AcpDelegationService delegationService;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    /**
     * endpointId → set of registered wrapper tool names. Keeps the tool
     * registry synced with the live endpoint set: when an endpoint is
     * toggled off/deleted, we know exactly which tools to remove.
     */
    private final ConcurrentHashMap<Long, Set<String>> registeredWrappers = new ConcurrentHashMap<>();

    @Autowired
    public AcpSkillBridge(AcpEndpointService endpointService,
                          AcpDelegationService delegationService,
                          ObjectMapper objectMapper,
                          @Lazy ToolRegistry toolRegistry) {
        this.endpointService = endpointService;
        this.delegationService = delegationService;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    public static boolean isVirtualAcpSkillId(Long id) {
        return id != null && (id & TAG_MASK) == VIRTUAL_ID_BASE;
    }

    public static Long extractEndpointId(Long virtualId) {
        if (!isVirtualAcpSkillId(virtualId)) return null;
        return virtualId & ID_MASK;
    }

    public static long virtualIdFor(AcpEndpointEntity endpoint) {
        long eid = endpoint.getId();
        if ((eid & TAG_MASK) != 0L) {
            throw new IllegalStateException(
                    "ACP endpoint id 0x" + Long.toHexString(eid)
                            + " uses the top two bits — would collide with the virtual id type tag");
        }
        return VIRTUAL_ID_BASE | eid;
    }

    @PostConstruct
    public void init() {
        log.info("AcpSkillBridge initialized (virtual ID range: {}+endpointId)", VIRTUAL_ID_BASE);
    }

    /**
     * Initial registration: enumerate enabled endpoints once the
     * application is fully bootstrapped. {@link ApplicationReadyEvent}
     * is preferred over {@code @PostConstruct} because the database
     * bootstrap (Flyway + seed data) finishes only after the context
     * comes up, and the endpoint table may be empty before that.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int registered = 0;
            for (AcpEndpointEntity ep : endpointService.listEnabled()) {
                registerWrappers(ep);
                registered++;
            }
            if (registered > 0) {
                log.info("AcpSkillBridge: registered wrappers for {} enabled endpoint(s)", registered);
            }
        } catch (Exception e) {
            log.warn("AcpSkillBridge initial registration failed: {}", e.getMessage());
        }
    }

    /**
     * Resync wrapper registrations on every endpoint lifecycle event.
     * Disabled / deleted endpoints have their wrappers removed; enabled
     * endpoints are re-registered (idempotent — we deregister-then-add
     * to avoid double registration when the user re-enables a row).
     */
    @EventListener(AcpEndpointChangedEvent.class)
    public void onEndpointChanged(AcpEndpointChangedEvent event) {
        Long endpointId = event.endpointId();
        if (endpointId == null) return;
        try {
            switch (event.type()) {
                case DELETED -> deregisterWrappers(endpointId);
                case CREATED, UPDATED, TOGGLED -> {
                    AcpEndpointEntity ep = safeGet(endpointId);
                    if (ep == null || !Boolean.TRUE.equals(ep.getEnabled())) {
                        deregisterWrappers(endpointId);
                    } else {
                        // Idempotent: drop stale set, then build fresh —
                        // covers args/env edits where the wrapper itself
                        // doesn't change but we still want a clean slate.
                        deregisterWrappers(endpointId);
                        registerWrappers(ep);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AcpSkillBridge resync for endpoint '{}' ({}) failed: {}",
                    event.name(), event.type(), e.getMessage());
        }
    }

    /**
     * Snapshot every enabled endpoint as a virtual {@link SkillEntity}.
     * Used by the Skills list endpoint; rows are non-persistent and
     * regenerated on each call.
     */
    public List<SkillEntity> listAcpDerivedSkillEntities() {
        return safeListEnabled().stream().map(this::endpointToEntity).toList();
    }

    /**
     * Snapshot every enabled endpoint as a virtual {@link ResolvedSkill}
     * with synthesized manifest. Status reflects the last connection
     * test on the row: OK → READY, ERROR / unknown → SETUP_NEEDED.
     */
    public List<ResolvedSkill> listAcpDerivedResolvedSkills() {
        return safeListEnabled().stream().map(this::endpointToResolved).toList();
    }

    /**
     * Lookup a single virtual ResolvedSkill by virtual id. Used by the
     * Skill detail drawer's reverse lookup path.
     */
    public ResolvedSkill findResolvedById(Long virtualId) {
        Long endpointId = extractEndpointId(virtualId);
        if (endpointId == null) return null;
        AcpEndpointEntity ep = safeGet(endpointId);
        return ep != null && Boolean.TRUE.equals(ep.getEnabled()) ? endpointToResolved(ep) : null;
    }

    public SkillEntity findEntityById(Long virtualId) {
        Long endpointId = extractEndpointId(virtualId);
        if (endpointId == null) return null;
        AcpEndpointEntity ep = safeGet(endpointId);
        return ep != null && Boolean.TRUE.equals(ep.getEnabled()) ? endpointToEntity(ep) : null;
    }

    // ==================== Tool registration ====================

    private void registerWrappers(AcpEndpointEntity ep) {
        if (ep == null || !Boolean.TRUE.equals(ep.getEnabled())) return;
        String slug = slugForEndpoint(ep);
        if (slug.isEmpty()) {
            log.warn("ACP endpoint id={} has blank name; cannot register wrapper", ep.getId());
            return;
        }
        String toolName = "acp_" + slug + "_prompt";
        String desc = String.format(
                "Delegate a prompt to the '%s' ACP coding agent. " +
                        "Send a single-string instruction; receive the agent's final reply.%s",
                ep.getName(),
                ep.getDescription() == null || ep.getDescription().isBlank()
                        ? ""
                        : " — " + ep.getDescription());
        // cwd stays optional in the schema so the LLM doesn't have to
        // invent a path. The server defaults to the endpoint's bound
        // workspace base_path when omitted (see AcpRuntimeSupport).
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"prompt\":{\"type\":\"string\",\"description\":\"the instruction or question to send\"},"
                + "\"cwd\":{\"type\":\"string\",\"description\":\"optional working directory; "
                + "defaults to the endpoint's workspace base path when omitted\"}"
                + "},"
                + "\"required\":[\"prompt\"]"
                + "}";

        final String endpointName = ep.getName();
        final Long endpointId = ep.getId();

        SkillScopedToolCallback callback = new SkillScopedToolCallback(toolName, desc, schema, input -> {
            try {
                JsonNode args = input == null || input.isBlank()
                        ? objectMapper.createObjectNode()
                        : objectMapper.readTree(input);
                String userPrompt = args.path("prompt").asText("").trim();
                if (userPrompt.isEmpty()) return errorJson("prompt is required");
                String cwdHint = args.path("cwd").asText("");
                String reply = delegationService.prompt(endpointName, userPrompt,
                        cwdHint == null || cwdHint.isBlank() ? null : cwdHint);
                JSONObject resp = new JSONObject()
                        .set("endpoint", endpointName)
                        .set("reply", reply);
                return JSONUtil.toJsonStr(resp);
            } catch (Exception e) {
                log.warn("ACP wrapper '{}' failed: {}", toolName, e.getMessage());
                return errorJson(e.getMessage() == null ? "delegation failed" : e.getMessage());
            }
        });

        // Availability supplier: re-check each agent tool-set build so
        // a toggle-off without a deregister call still hides the tool.
        toolRegistry.registerPluginTool(callback, () -> {
            AcpEndpointEntity live = safeGet(endpointId);
            return live != null && Boolean.TRUE.equals(live.getEnabled());
        });
        registeredWrappers.computeIfAbsent(endpointId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
        log.info("AcpSkillBridge: registered wrapper '{}' for endpoint '{}'", toolName, endpointName);
    }

    private void deregisterWrappers(Long endpointId) {
        Set<String> names = registeredWrappers.remove(endpointId);
        if (names == null || names.isEmpty()) return;
        for (String name : names) {
            try {
                toolRegistry.unregisterPluginTool(name);
            } catch (Exception e) {
                log.debug("Unregister ACP wrapper '{}' failed: {}", name, e.getMessage());
            }
        }
        log.info("AcpSkillBridge: deregistered {} wrapper(s) for endpoint id={}", names.size(), endpointId);
    }

    // ==================== Synthesis ====================

    private SkillEntity endpointToEntity(AcpEndpointEntity ep) {
        SkillEntity s = new SkillEntity();
        s.setId(virtualIdFor(ep));
        s.setName(slugForEndpoint(ep));
        s.setNameEn(displayName(ep));
        s.setNameZh(ep.getDescription() != null && !ep.getDescription().isBlank()
                ? displayName(ep) : null);
        s.setDescription(buildDescription(ep));
        s.setSkillType("acp");
        s.setIcon(iconFor(ep));
        s.setVersion("1.0.0");
        s.setAuthor("acp-bridge");
        s.setEnabled(Boolean.TRUE.equals(ep.getEnabled()));
        s.setBuiltin(Boolean.TRUE.equals(ep.getBuiltin()));
        s.setTags("acp");
        // Carry the backing endpoint's workspace through to the virtual
        // SkillEntity so binding-time tenancy checks can compare it against
        // the agent's workspace. Without this the bridge synthesizes rows
        // with workspaceId = null and an agent in any workspace could bind
        // any ACP endpoint regardless of where the endpoint was provisioned.
        s.setWorkspaceId(ep.getWorkspaceId());
        s.setSecurityScanStatus("PASSED"); // ACP endpoints are user-configured external CLIs, not skill scripts
        s.setConfigJson(buildConfigJson(ep));
        s.setManifestJson(serializeManifest(buildManifest(ep)));
        s.setSkillContent(buildSkillContent(ep));
        return s;
    }

    private ResolvedSkill endpointToResolved(AcpEndpointEntity ep) {
        SkillManifest manifest = buildManifest(ep);
        boolean ok = "OK".equalsIgnoreCase(nullSafe(ep.getLastStatus()));
        boolean errored = "ERROR".equalsIgnoreCase(nullSafe(ep.getLastStatus()))
                || (ep.getLastError() != null && !ep.getLastError().isBlank());
        // "Unknown" (untested) endpoints are treated as READY so the user
        // can call them without an explicit Test click — unlike MCP, an
        // ACP CLI is spawned per call so an untested-but-installed CLI
        // works fine on first invocation. ERROR keeps SETUP_NEEDED.
        boolean ready = !errored;

        Map<String, String> featureStatuses = new LinkedHashMap<>();
        featureStatuses.put("default", ready ? "READY" : "SETUP_NEEDED");
        Set<String> active = new LinkedHashSet<>();
        if (ready) active.add("default");

        List<String> missing = new ArrayList<>();
        if (!ready) {
            missing.add("acp:" + ep.getName() + " (status: " + nullSafe(ep.getLastStatus()) + ")");
        }

        String summary = ok
                ? "ACP endpoint '" + ep.getName() + "' tested OK"
                : (errored
                    ? "ACP endpoint '" + ep.getName() + "' last test failed: " + nullSafe(ep.getLastError())
                    : "ACP endpoint '" + ep.getName() + "' not yet tested — calls will spawn the CLI on demand");

        return ResolvedSkill.builder()
                .id(virtualIdFor(ep))
                .name(slugForEndpoint(ep))
                .description(buildDescription(ep))
                .content(buildSkillContent(ep))
                .source("acp")
                .skillDir(null)
                .configuredSkillDir(null)
                .runtimeAvailable(ready)
                .resolutionError(ready ? null : nullSafe(ep.getLastError()))
                .references(Map.of())
                .scripts(Map.of())
                .enabled(Boolean.TRUE.equals(ep.getEnabled()))
                .icon(iconFor(ep))
                .builtin(Boolean.TRUE.equals(ep.getBuiltin()))
                .securityBlocked(false)
                .securitySummary("ACP-derived skill (external CLI; not subject to SKILL.md scanning)")
                .dependencyReady(ready)
                .missingDependencies(missing)
                .dependencySummary(summary)
                .manifest(manifest)
                .featureStatuses(featureStatuses)
                .activeFeatures(active)
                .build();
    }

    /**
     * Synthesize a §10.2 minimal manifest from the live endpoint row.
     * The wrapper tool name {@code acp_<slug>_prompt} is the single
     * advertised tool, surfaced via {@code allowedTools} + the default
     * feature so {@code ResolvedSkill.getEffectiveAllowedTools()} picks
     * it up the same way as a hand-authored skill manifest.
     */
    private SkillManifest buildManifest(AcpEndpointEntity ep) {
        String slug = slugForEndpoint(ep);
        String toolName = "acp_" + slug + "_prompt";
        List<String> tools = List.of(toolName);

        SkillManifest.FeatureDef defaultFeature = SkillManifest.FeatureDef.builder()
                .id("default")
                .label(displayName(ep))
                .requires(List.of("acp:" + ep.getName()))
                .platforms(List.of())
                .tools(tools)
                .build();

        SkillManifest.RequirementDef acpRequirement = SkillManifest.RequirementDef.builder()
                .key("acp:" + ep.getName())
                .type("acp")
                .check(ep.getName())
                .description("ACP endpoint '" + ep.getName() + "' must be enabled and reachable. "
                        + "Configure in Settings ▸ ACP Endpoints.")
                .build();

        SkillManifest.AcpBinding binding = SkillManifest.AcpBinding.builder()
                .endpoint(ep.getName())
                .resolvedEndpointId(ep.getId())
                .build();

        return SkillManifest.builder()
                .id(slug)
                .name(slug)
                .description(buildDescription(ep))
                .icon(iconFor(ep))
                .version("1.0.0")
                .author("acp-bridge")
                .type("acp")
                .category("system")
                .allowedTools(tools)
                .requires(List.of(acpRequirement))
                .features(List.of(defaultFeature))
                .acp(binding)
                .selfEvolution(SkillManifest.SelfEvolution.builder()
                        // Bridged ACP cards don't author LESSONS.md — the
                        // upstream agent owns its own self-evolution.
                        .lessonsEnabled(false)
                        .lessonsMaxEntries(0)
                        .memoryWritesAllowed(true)
                        .build())
                .extras(Map.of("acpEndpointId", ep.getId()))
                .build();
    }

    /**
     * Synthesize a SKILL.md body for an ACP-derived virtual skill.
     *
     * <p>ACP endpoints carry no hand-authored SKILL.md — they wrap an
     * external coding-agent CLI rather than a skill package. Without a
     * synthesized body, an agent that calls
     * {@code readSkillFile(skillName=..., filePath="SKILL.md")} gets
     * nothing beyond the one-line description and cannot tell how to
     * drive the endpoint.
     *
     * <p>This builds a markdown brief from the live endpoint row: what
     * the endpoint is, the single wrapper tool it exposes, that tool's
     * arguments, and usage notes — so the LLM can call
     * {@code acp_<slug>_prompt} correctly on the first attempt.
     */
    private String buildSkillContent(AcpEndpointEntity ep) {
        String slug = slugForEndpoint(ep);
        String toolName = "acp_" + slug + "_prompt";
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(displayName(ep)).append("\n\n");
        sb.append(buildDescription(ep)).append("\n\n");

        sb.append("## Overview\n\n");
        sb.append("This skill delegates work to the **").append(ep.getName())
                .append("** ACP (Agent Communication Protocol) coding agent. ")
                .append("The agent runs as an external CLI process spawned on demand: ")
                .append("send it a single natural-language instruction and it returns ")
                .append("its final reply.\n\n");

        sb.append("## Tools\n\n");
        sb.append("### `").append(toolName).append("`\n\n");
        sb.append("Delegate a prompt to the '").append(ep.getName())
                .append("' coding agent and receive its final reply.\n\n");
        sb.append("Parameters:\n\n");
        sb.append("- `prompt` (string, required) — the instruction or question to send.\n");
        sb.append("- `cwd` (string, optional) — working directory; defaults to the ")
                .append("endpoint's workspace base path when omitted.\n\n");

        sb.append("## Usage notes\n\n");
        sb.append("- Call `").append(toolName).append("` with one self-contained instruction. ")
                .append("The endpoint runs autonomously and returns only its final answer, ")
                .append("not intermediate steps.\n");
        sb.append("- Omit `cwd` unless the task needs a specific directory — the server ")
                .append("resolves the endpoint's bound workspace path.\n");
        if (Boolean.TRUE.equals(ep.getTrusted())) {
            sb.append("- This endpoint is trusted: the agent's own tool calls are accepted ")
                    .append("without re-prompting for approval.\n");
        } else {
            sb.append("- This endpoint is not trusted: the agent's tool calls may require ")
                    .append("human approval before they run.\n");
        }
        String status = nullSafe(ep.getLastStatus());
        if ("OK".equalsIgnoreCase(status)) {
            sb.append("- Last connection test: OK.\n");
        } else if ("ERROR".equalsIgnoreCase(status)
                || (ep.getLastError() != null && !ep.getLastError().isBlank())) {
            sb.append("- Last connection test failed: ").append(nullSafe(ep.getLastError()))
                    .append(". The CLI may not be installed or reachable.\n");
        } else {
            sb.append("- Not yet tested — the CLI is spawned on the first call.\n");
        }

        return sb.toString();
    }

    // ==================== Helpers ====================

    private List<AcpEndpointEntity> safeListEnabled() {
        try {
            return endpointService.listEnabled();
        } catch (Exception e) {
            log.warn("AcpSkillBridge could not list enabled endpoints: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private AcpEndpointEntity safeGet(Long id) {
        try {
            return endpointService.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    private String slugify(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    /**
     * Stable slug for an ACP endpoint. Falls back to {@code acp-{id}} when
     * the source name has no ASCII letter/digit (e.g. pure CJK), because
     * the naive slugify would otherwise return a run of dashes and two
     * differently-named all-CJK endpoints would collide on the same slug,
     * which is also the basis for the {@code acp_<slug>_prompt} wrapper
     * tool name registered in the global tool registry.
     */
    private String slugForEndpoint(AcpEndpointEntity ep) {
        String slug = slugify(ep.getName());
        return hasAsciiAlphaNumeric(slug) ? slug : "acp-" + ep.getId();
    }

    private static boolean hasAsciiAlphaNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
    }

    private String displayName(AcpEndpointEntity ep) {
        if (ep.getDisplayName() != null && !ep.getDisplayName().isBlank()) return ep.getDisplayName();
        return ep.getName() != null ? ep.getName() : "acp-" + ep.getId();
    }

    private String buildDescription(AcpEndpointEntity ep) {
        if (ep.getDescription() != null && !ep.getDescription().isBlank()) return ep.getDescription();
        return "ACP coding agent '" + ep.getName() + "' — delegate prompts via the "
                + "auto-registered tool. Configure in Settings ▸ ACP Endpoints.";
    }

    private String iconFor(AcpEndpointEntity ep) {
        // Light heuristic — match the most popular ACP runners by name.
        String n = nullSafe(ep.getName()).toLowerCase(Locale.ROOT);
        if (n.contains("claude")) return "🟠";
        if (n.contains("codex") || n.contains("openai")) return "⚪";
        if (n.contains("qwen")) return "🔵";
        if (n.contains("gemini") || n.contains("google")) return "🟡";
        if (n.contains("opencode")) return "🟢";
        return "🤝";
    }

    private String buildConfigJson(AcpEndpointEntity ep) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "acpEndpointId", ep.getId(),
                    "command", nullSafe(ep.getCommand()),
                    "trusted", Boolean.TRUE.equals(ep.getTrusted()),
                    "source", Map.of("type", "acp")));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeManifest(SkillManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String errorJson(String msg) {
        return JSONUtil.createObj().set("error", msg).toString();
    }
}
