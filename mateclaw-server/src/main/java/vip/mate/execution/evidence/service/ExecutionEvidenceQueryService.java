package vip.mate.execution.evidence.service;

import org.springframework.stereotype.Service;
import vip.mate.execution.evidence.ExecutionEvidenceProperties;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.EffectOutcome;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.model.SourceLevel;
import vip.mate.auth.service.AuthService;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.WorkspaceService;
import io.micrometer.core.instrument.MeterRegistry;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.model.ExecutionEvidence;
import vip.mate.execution.evidence.model.ExecutionAttempt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class ExecutionEvidenceQueryService {
    public record View(Long id, Long attemptId, String conversationId, String toolName, AttemptState state,
                       EffectOutcome effectOutcome, EvidenceKind kind, EvidenceResult result, SourceLevel sourceLevel,
                       String validity, String summary, Instant observedAt, Instant expiresAt,
                       String artifactRef, String artifactDigest, String checkScope) { }
    public record Page(List<View> items, String nextCursor) { }
    private record Cursor(Instant observedAt, Long id) { }
    private final ExecutionEvidenceStore store;
    private final ConversationService conversations;
    private final TeamWorkerConversationGovernanceService teams;
    private final GeneratedFileCache files;
    private final AuthService auth;
    private final WorkspaceService workspaces;
    private final ExecutionEvidenceProperties properties;
    private final MeterRegistry metrics;

    public ExecutionEvidenceQueryService(ExecutionEvidenceStore store, ConversationService conversations,
            TeamWorkerConversationGovernanceService teams, GeneratedFileCache files,
            AuthService auth, WorkspaceService workspaces, ExecutionEvidenceProperties properties, MeterRegistry metrics) {
        this.store = store;
        this.conversations = conversations;
        this.teams = teams;
        this.files = files;
        this.auth = auth;
        this.workspaces = workspaces;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Page list(String username, Long workspaceId, String conversationId, String cursor, Integer limit,
                     Long goalId, Long teamTaskId) {
        long started = System.nanoTime();
        try {
            Long canonicalWorkspace = authorize(username, workspaceId, conversationId);
            int bounded = Math.clamp(limit == null ? properties.getDefaultListLimit() : limit, 1, properties.getMaxListLimit());
            Cursor before = decode(cursor);
            List<ExecutionEvidence> rows = store.list(canonicalWorkspace, conversationId, before.observedAt(),
                    before.id(), bounded + 1, goalId, teamTaskId);
            boolean hasMore = rows.size() > bounded;
            List<ExecutionEvidence> page = rows.stream().limit(bounded).toList();
            if (page.isEmpty()) return new Page(List.of(), null);
            var attempts = store.findAttempts(canonicalWorkspace, conversationId,
                    page.stream().map(ExecutionEvidence::attemptId).distinct().toList());
            return new Page(page.stream().map(row -> view(username, row, attempts.get(row.attemptId()), false)).toList(),
                    hasMore ? encode(page.getLast()) : null);
        } finally {
            metrics.timer("mateclaw.execution.evidence.query.latency").record(
                    System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public View detail(String username, Long workspaceId, Long id) {
        return authorizedDetail(username, workspaceId, id, true);
    }

    private View authorizedDetail(String username, Long workspaceId, Long id, boolean inspectVersion) {
        if (username == null || username.isBlank() || id == null) throw hidden();
        ExecutionEvidence row = store.findById(id).orElseThrow(this::hidden);
        Long canonicalWorkspace = authorize(username, workspaceId, row.conversationId());
        if (!canonicalWorkspace.equals(row.workspaceId())) throw hidden();
        return view(username, row, store.findAttempt(row.attemptId()).orElseThrow(this::hidden), inspectVersion);
    }

    public JsonArtifactRecipe.Result checkJson(String username, Long workspaceId, Long id, List<String> fields) {
        // Reuse source, attempt and file authorization before any content read.
        View view = authorizedDetail(username, workspaceId, id, false);
        List<String> required = JsonArtifactRecipe.validate(fields);
        if (view.kind() != EvidenceKind.ARTIFACT_SNAPSHOT || view.artifactRef() == null
                || "UNAVAILABLE".equals(view.validity())) {
            return JsonArtifactRecipe.outcome("UNAVAILABLE", required, List.of());
        }
        Long ownerWorkspace = authorize(username, workspaceId, view.conversationId());
        var read = files.readDurableArtifactSnapshot(view.artifactRef(), ownerWorkspace, view.conversationId(),
                view.artifactDigest(), properties.getArtifactVersionCheckMaxBytes());
        return "READ".equals(read.status()) ? JsonArtifactRecipe.check(read.bytes(), required)
                : JsonArtifactRecipe.outcome(read.status(), required, List.of());
    }

    private Long authorize(String username, Long workspaceId, String conversationId) {
        if (username == null || username.isBlank() || conversationId == null || conversationId.isBlank()) throw hidden();
        var conversation = conversations.findByConversationId(conversationId);
        if (conversation == null || conversation.getWorkspaceId() == null || Integer.valueOf(1).equals(conversation.getDeleted())
                || workspaceId != null && !workspaceId.equals(conversation.getWorkspaceId())) throw hidden();
        if (!conversations.isConversationOwner(conversationId, username)
                && !teams.canReadTranscript(conversationId, null, null, username)) throw hidden();
        return conversation.getWorkspaceId();
    }

    private View view(String username, ExecutionEvidence row, ExecutionAttempt attempt, boolean inspectVersion) {
        if (attempt == null || !Objects.equals(attempt.id(), row.attemptId())) throw hidden();
        if (!Objects.equals(attempt.identity().workspaceId(), row.workspaceId())
                || !Objects.equals(attempt.identity().conversationId(), row.conversationId())) throw hidden();
        var evidence = row.observation();
        String validity = "UNKNOWN";
        String artifact = evidence.artifactRef();
        String digest = evidence.artifactDigest();
        String summary = evidence.summary();
        if (evidence.expiresAt() != null && !evidence.expiresAt().isAfter(Instant.now())) validity = "UNAVAILABLE";
        if (evidence.kind() == EvidenceKind.ARTIFACT_SNAPSHOT) {
            var user = auth.findByUsername(username);
            boolean canReadFile = user != null && ("admin".equalsIgnoreCase(user.getRole())
                    || workspaces.hasPermissionCached(row.workspaceId(), user.getId(), "viewer"));
            if (!canReadFile) {
                artifact = null;
                digest = null;
                summary = null;
                validity = "UNAVAILABLE";
            } else if (!files.isDurablyAvailable(artifact, row.workspaceId(), row.conversationId())) {
                validity = "UNAVAILABLE";
                artifact = null;
            } else if (inspectVersion && !"UNAVAILABLE".equals(validity)) {
                var version = files.probeDurableArtifactVersion(artifact, row.workspaceId(), row.conversationId(),
                        digest, properties.getArtifactVersionCheckMaxBytes());
                if (version == GeneratedFileCache.ArtifactVersion.CHANGED) {
                    validity = "STALE";
                } else if (version == GeneratedFileCache.ArtifactVersion.UNAVAILABLE) {
                    validity = "UNAVAILABLE";
                    artifact = null;
                }
                // Equality/budget exhaustion remains UNKNOWN; there is no managed generation fence.
            }
        }
        metrics.counter("mateclaw.execution.evidence.validity", "status", validity).increment();
        // An available observation is not a freshness or correctness certificate.
        return new View(row.id(), row.attemptId(), row.conversationId(), attempt.identity().toolName(), attempt.state(),
                attempt.effectOutcome(), evidence.kind(), evidence.result(), evidence.sourceLevel(), validity,
                summary, evidence.observedAt(), evidence.expiresAt(), artifact, digest, evidence.checkScope());
    }

    private Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Cursor(null, null);
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            long id = Long.parseLong(parts[1]);
            if (id <= 0) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), id);
        } catch (RuntimeException invalid) {
            throw new MateClawException(400, "Invalid execution evidence cursor");
        }
    }

    private String encode(ExecutionEvidence row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (row.observation().observedAt() + "|" + row.id()).getBytes(StandardCharsets.UTF_8));
    }

    private MateClawException hidden() {
        metrics.counter("mateclaw.execution.evidence.query.denied").increment();
        return new MateClawException(404, "Execution evidence not found");
    }
}
