package vip.mate.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class StructuredMemoryCandidateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("turn-local word-count constraints are not durable memory")
    void rejectsTurnLocalWordCountConstraint() throws Exception {
        var candidate = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"user","key":"preferred_word_count","content":"本次回答不少于 3000 字",
                 "scope":"turn","stability":"transient","confidence":0.95,
                 "evidence_count":1,"expires_at":null,"explicitly_persistent":false}
                """));

        assertTrue(candidate.isPresent());
        assertFalse(candidate.orElseThrow().isAdmissible(LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("an explicitly persistent durable user preference is admitted")
    void acceptsExplicitDurablePreference() throws Exception {
        var candidate = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"user","key":"preferred_language","content":"以后默认使用中文回答",
                 "scope":"user","stability":"durable","confidence":0.95,
                 "evidence_count":1,"expires_at":null,"explicitly_persistent":true}
                """));

        assertTrue(candidate.orElseThrow().isAdmissible(LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("repeated durable evidence can admit a preference without explicit persistence")
    void acceptsRepeatedDurableEvidence() throws Exception {
        var candidate = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"feedback","key":"avoid_mock_data","content":"用户反复纠正：不要使用 mock 数据",
                 "scope":"user","stability":"durable","confidence":0.9,
                 "evidence_count":2,"expires_at":null,"explicitly_persistent":false}
                """));

        assertTrue(candidate.orElseThrow().isAdmissible(LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("ongoing project context remains eligible for query-conditioned memory")
    void acceptsOngoingProjectContext() throws Exception {
        var candidate = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"project","key":"project_codename","content":"项目代号是天枢",
                 "scope":"project","stability":"ongoing","confidence":0.85,
                 "evidence_count":1,"expires_at":null,"explicitly_persistent":false}
                """));

        assertTrue(candidate.orElseThrow().isAdmissible(LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("expired and incomplete candidates are rejected")
    void rejectsExpiredAndIncompleteCandidates() throws Exception {
        var expired = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"reference","key":"sprint_board","content":"看板地址：https://example.test",
                 "scope":"project","stability":"ongoing","confidence":0.9,
                 "evidence_count":1,"expires_at":"2026-08-31","explicitly_persistent":false}
                """));
        var incomplete = StructuredMemoryCandidate.fromJson(mapper.readTree("""
                {"type":"user","key":"preferred_language","content":"使用中文"}
                """));

        assertFalse(expired.orElseThrow().isAdmissible(LocalDate.of(2026, 9, 1)));
        assertTrue(incomplete.isEmpty(), "auto-extracted candidates must carry complete durability metadata");
    }
}
