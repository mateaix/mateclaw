package vip.mate.execution.evidence;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vip.mate.config.JacksonConfig;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.model.*;
import vip.mate.execution.evidence.service.*;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEvidenceQueryTest {
    private final ExecutionEvidenceStore store = mock(ExecutionEvidenceStore.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final TeamWorkerConversationGovernanceService teams = mock(TeamWorkerConversationGovernanceService.class);
    private final GeneratedFileCache files = mock(GeneratedFileCache.class);
    private ExecutionEvidenceQueryService queries;
    private final long id = 1999999999999999999L;
    private final Instant now = Instant.parse("2026-09-07T00:00:00Z");

    @BeforeEach void setup() {
        queries = new ExecutionEvidenceQueryService(store, conversations, teams, files,
                mock(AuthService.class), mock(WorkspaceService.class), new ExecutionEvidenceProperties(), new SimpleMeterRegistry());
        var conversation = new ConversationEntity();
        conversation.setConversationId("conv"); conversation.setWorkspaceId(1L); conversation.setDeleted(0);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        when(conversations.isConversationOwner("conv", "owner")).thenReturn(true);
        var attempt = new ExecutionAttempt(1L,
                new ExecutionIdentity(1L, "conv", "native", null, "call", "call", 1, "provider", "tool",
                        null, null, null, null, null, null, "fence"),
                AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN, now, now);
        when(store.findAttempt(1L)).thenReturn(Optional.of(attempt));
        when(store.findAttempts(1L, "conv", List.of(1L))).thenReturn(Map.of(1L, attempt));
    }

    @Test void deniesUnscopedAnonymousAndWrongWorkspaceBeforeReadingEvidence() {
        assertEquals(404, assertThrows(MateClawException.class, () -> list("stranger", 1L, "conv", null, 20)).getCode());
        assertEquals(404, assertThrows(MateClawException.class, () -> list("owner", 2L, "conv", null, 20)).getCode());
        assertThrows(MateClawException.class, () -> list(null, 1L, "conv", null, 20));
        assertThrows(MateClawException.class, () -> list("owner", 1L, "", null, 20));
        verifyNoInteractions(store);
    }

    @Test void detailDoesNotLeakCrossWorkspaceOrForeignConversation() {
        when(store.findById(id)).thenReturn(Optional.of(evidence(id)));
        assertEquals(404, assertThrows(MateClawException.class, () -> queries.detail("stranger", 1L, id)).getCode());
        assertEquals(404, assertThrows(MateClawException.class, () -> queries.detail("owner", 2L, id)).getCode());
        assertEquals(404, assertThrows(MateClawException.class, () -> queries.detail("owner", 1L, 42L)).getCode());
    }

    @Test void limitsAndCursorPreserveFullPrecisionAndNeverClaimVerification() {
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(2), isNull(), isNull()))
                .thenReturn(List.of(evidence(id), evidence(id - 1)));
        var page = list("owner", 1L, "conv", null, 1);
        assertEquals(id, page.items().getFirst().id());
        assertEquals("UNKNOWN", page.items().getFirst().validity());
        assertNotNull(page.nextCursor());
        when(store.list(eq(1L), eq("conv"), eq(now), eq(id), eq(2), isNull(), isNull()))
                .thenReturn(List.of(evidence(id - 1)));
        var next = list("owner", 1L, "conv", page.nextCursor(), 1);
        assertEquals(id - 1, next.items().getFirst().id());
        assertNull(next.nextCursor());
        assertThrows(MateClawException.class, () -> list("owner", 1L, "conv", "invalid-cursor", 1));
    }

    @Test void canonicalTeamTranscriptReaderCanQueryWorkerEvidence() {
        when(teams.canReadTranscript("conv", null, null, "reviewer")).thenReturn(true);
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(21), isNull(), isNull())).thenReturn(List.of(evidence(id)));
        assertEquals(1, list("reviewer", 1L, "conv", null, null).items().size());
    }

    @Test void jsonUsesStringIdentifiersAndMissingArtifactsAreUnavailable() throws Exception {
        var builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        var mapper = builder.build();
        when(store.findById(id)).thenReturn(Optional.of(evidence(id)));
        var json = mapper.readTree(mapper.writeValueAsString(queries.detail("owner", 1L, id)));
        assertTrue(json.get("id").isTextual());
        assertEquals(Long.toString(id), json.get("id").asText());
        var artifact = new EvidenceObservation("artifact:missing", EvidenceKind.ARTIFACT_SNAPSHOT,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null,
                null, "missing", "digest", "file metadata", null, now, now.minusSeconds(1));
        when(store.findById(id)).thenReturn(Optional.of(new ExecutionEvidence(id, 1L, 1L, "conv", artifact)));
        var unavailable = queries.detail("owner", 1L, id);
        assertEquals("UNAVAILABLE", unavailable.validity());
        assertNull(unavailable.artifactRef());
        assertNull(unavailable.artifactDigest());
        verifyNoInteractions(files);
    }

    @Test void emptyPageDoesNotLoadAttempts() {
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(21), isNull(), isNull()))
                .thenReturn(List.of());
        assertTrue(list("owner", 1L, "conv", null, null).items().isEmpty());
        verify(store, never()).findAttempts(any(), any(), any());
        verify(store, never()).findAttempt(any());
    }

    @Test void repeatedAttemptIsLoadedOnceAndLookaheadIsExcluded() {
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(3), isNull(), isNull()))
                .thenReturn(List.of(evidence(id), evidence(id - 1),
                        new ExecutionEvidence(id - 2, 1L, 999L, "conv", evidence(id).observation())));
        var page = list("owner", 1L, "conv", null, 2);
        assertEquals(List.of(id, id - 1), page.items().stream().map(ExecutionEvidenceQueryService.View::id).toList());
        assertNotNull(page.nextCursor());
        verify(store).findAttempts(1L, "conv", List.of(1L));
        verify(store, never()).findAttempt(any());
    }

    @Test void missingOrMismatchedBatchAttemptFailsClosed() {
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(21), isNull(), isNull()))
                .thenReturn(List.of(evidence(id)));
        when(store.findAttempts(1L, "conv", List.of(1L))).thenReturn(Map.of());
        assertEquals(404, assertThrows(MateClawException.class,
                () -> list("owner", 1L, "conv", null, null)).getCode());
        var foreign = new ExecutionAttempt(1L, new ExecutionIdentity(2L, "other", "native", null,
                "call", "call", 1, "provider", "tool", null, null, null, null, null, null, "fence"),
                AttemptState.SUCCEEDED, EffectOutcome.UNCERTAIN, now, now);
        when(store.findAttempts(1L, "conv", List.of(1L))).thenReturn(Map.of(1L, foreign));
        assertEquals(404, assertThrows(MateClawException.class,
                () -> list("owner", 1L, "conv", null, null)).getCode());
    }

    @Test void detailDetectsChangedPersistedArtifact(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var cache = new GeneratedFileCache(root);
        byte[] bytes = "original report".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String artifactId = cache.put(bytes, "report.txt", "text/plain", new GeneratedFileCache.Owner(1L, 1L, "conv"));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var observation = new EvidenceObservation("artifact:" + artifactId, EvidenceKind.ARTIFACT_SNAPSHOT,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null,
                null, artifactId, digest, "registered file", null, Instant.now(), Instant.now().plusSeconds(3600));
        when(store.findById(id)).thenReturn(Optional.of(new ExecutionEvidence(id, 1L, 1L, "conv", observation)));
        var auth = mock(AuthService.class);
        var admin = new vip.mate.auth.model.UserEntity();
        admin.setId(1L); admin.setRole("admin");
        when(auth.findByUsername("owner")).thenReturn(admin);
        var properties = new ExecutionEvidenceProperties();
        queries = new ExecutionEvidenceQueryService(store, conversations, teams, cache, auth,
                mock(WorkspaceService.class), properties, new SimpleMeterRegistry());
        assertEquals("UNKNOWN", queries.detail("owner", 1L, id).validity());
        java.nio.file.Files.writeString(root.resolve(artifactId), "externally replaced");
        assertEquals("STALE", queries.detail("owner", 1L, id).validity());
        when(store.list(eq(1L), eq("conv"), isNull(), isNull(), eq(21), isNull(), isNull()))
                .thenReturn(List.of(new ExecutionEvidence(id, 1L, 1L, "conv", observation)));
        assertEquals("UNKNOWN", list("owner", 1L, "conv", null, 20).items().getFirst().validity(),
                "pagination must remain metadata-only");
        properties.setArtifactVersionCheckMaxBytes(0);
        assertEquals("UNKNOWN", queries.detail("owner", 1L, id).validity());
    }

    @Test void jsonChecksAreSourceAuthorizedAndDoNotInspectUnavailableFiles() {
        when(store.findById(id)).thenReturn(Optional.of(evidence(id)));
        assertThrows(MateClawException.class, () -> queries.checkJson("stranger", 1L, id, List.of("report")));
        assertThrows(MateClawException.class, () -> queries.checkJson("owner", 2L, id, List.of("report")));
        assertEquals("UNAVAILABLE", queries.checkJson("owner", 1L, id, List.of("report")).status());
        var artifact = new EvidenceObservation("artifact:private", EvidenceKind.ARTIFACT_SNAPSHOT,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null,
                null, "private", "digest", "private metadata", null, now, null);
        when(store.findById(id)).thenReturn(Optional.of(new ExecutionEvidence(id, 1L, 1L, "conv", artifact)));
        assertEquals("UNAVAILABLE", queries.checkJson("owner", 1L, id, List.of("report")).status());
        verifyNoInteractions(files);
    }

    @Test void jsonCheckUsesRealDurableBytesWithoutPromotingEvidence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var cache = new GeneratedFileCache(root);
        byte[] bytes = "{\"report\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String artifactId = cache.put(bytes, "report.json", "application/json", new GeneratedFileCache.Owner(1L, 1L, "conv"));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var observation = new EvidenceObservation("artifact:" + artifactId, EvidenceKind.ARTIFACT_SNAPSHOT,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null,
                null, artifactId, digest, "registered file", null, Instant.now(), Instant.now().plusSeconds(3600));
        when(store.findById(id)).thenReturn(Optional.of(new ExecutionEvidence(id, 1L, 1L, "conv", observation)));
        var auth = mock(AuthService.class);
        var user = new vip.mate.auth.model.UserEntity(); user.setId(1L); user.setRole("user");
        when(auth.findByUsername("owner")).thenReturn(user);
        var workspaces = mock(WorkspaceService.class);
        when(workspaces.hasPermissionCached(1L, 1L, "viewer")).thenReturn(true);
        var properties = new ExecutionEvidenceProperties();
        queries = new ExecutionEvidenceQueryService(store, conversations, teams, cache, auth,
                workspaces, properties, new SimpleMeterRegistry());
        var result = queries.checkJson("owner", 1L, id, List.of("report"));
        assertEquals("MATCH", result.status());
        assertFalse(result.acceptanceEligible());
        assertEquals("UNKNOWN", queries.detail("owner", 1L, id).validity());
        assertEquals("MISSING_FIELDS", queries.checkJson("owner", 1L, id, List.of("appendix")).status());
        properties.setArtifactVersionCheckMaxBytes(0);
        assertEquals("UNKNOWN", queries.checkJson("owner", 1L, id, List.of("report")).status());
        properties.setArtifactVersionCheckMaxBytes(1024);
        java.nio.file.Files.writeString(root.resolve(artifactId), "{\"report\":false}");
        assertEquals("STALE", queries.checkJson("owner", 1L, id, List.of("report")).status());
        when(workspaces.hasPermissionCached(1L, 1L, "viewer")).thenReturn(false);
        assertEquals("UNAVAILABLE", queries.checkJson("owner", 1L, id, List.of("report")).status());
    }

    private ExecutionEvidenceQueryService.Page list(String user, Long workspace, String conversation, String cursor, Integer limit) {
        return queries.list(user, workspace, conversation, cursor, limit, null, null);
    }

    private ExecutionEvidence evidence(long evidenceId) {
        return new ExecutionEvidence(evidenceId, 1L, 1L, "conv", new EvidenceObservation("callback", EvidenceKind.TOOL_RETURNED,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null, null,
                null, null, "Tool callback returned", null, now, null));
    }
}
