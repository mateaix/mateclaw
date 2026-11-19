package vip.mate.skill.lessons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.skill.lessons.event.SkillLessonWrittenEvent;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RFC-090 §11.4 / §14.3 — locked-in behaviour for LESSONS.md writes:
 *
 *  <ol>
 *    <li>First write creates the file with the canonical header and the
 *        new section appended.</li>
 *    <li>Subsequent writes append; SkillLessonWrittenEvent fires once
 *        per recorded lesson.</li>
 *    <li>FIFO truncation kicks in beyond {@code maxEntries}.</li>
 *    <li>{@code clearLessons} removes the file outright.</li>
 *    <li>Events are NOT MemoryWriteEvent — the SOUL summarizer must
 *        not see them (§14.3).</li>
 *  </ol>
 */
class SkillLessonsServiceTest {

    @TempDir
    Path tempDir;

    private SkillWorkspaceManager workspaceManager;
    private ApplicationEventPublisher publisher;
    private SkillLessonsService service;
    private List<Object> publishedEvents;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(SkillWorkspaceManager.class);
        publishedEvents = new ArrayList<>();
        publisher = event -> publishedEvents.add(event);
        when(workspaceManager.resolveConventionPath(anyString(), any()))
                .thenAnswer(inv -> tempDir.resolve(inv.getArgument(0, String.class)));
        service = new SkillLessonsService(workspaceManager, publisher);
    }

    @Test
    @DisplayName("first write creates file with canonical header and section")
    void firstWriteCreatesFile() throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve("clip-generator"));
        ResolvedSkill skill = ResolvedSkill.builder()
                .id(1L).name("clip-generator").skillDir(skillDir).build();

        String id = service.recordLesson(skill, 99L, "conv-1", "Trim cuts on dialogue beats", 50);
        assertNotNull(id);

        String contents = Files.readString(skillDir.resolve("LESSONS.md"), StandardCharsets.UTF_8);
        assertTrue(contents.startsWith("# Lessons learned for clip-generator"));
        assertTrue(contents.contains("Trim cuts on dialogue beats"));
        assertTrue(contents.contains("(conversation: conv-1)"));
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof SkillLessonWrittenEvent);
        SkillLessonWrittenEvent ev = (SkillLessonWrittenEvent) publishedEvents.get(0);
        assertEquals(99L, ev.agentId());
        assertEquals(1L, ev.skillId());
        assertEquals("clip-generator", ev.skillName());
    }

    @Test
    @DisplayName("two writes produce two sections under one header")
    void twoWritesAppend() throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve("s1"));
        ResolvedSkill skill = ResolvedSkill.builder().id(1L).name("s1").skillDir(skillDir).build();

        service.recordLesson(skill, 1L, "c1", "first", 50);
        service.recordLesson(skill, 1L, "c2", "second", 50);

        String contents = Files.readString(skillDir.resolve("LESSONS.md"), StandardCharsets.UTF_8);
        long sectionCount = contents.lines().filter(l -> l.startsWith("## ")).count();
        assertEquals(2, sectionCount);
        assertEquals(2, publishedEvents.size());
    }

    @Test
    @DisplayName("FIFO truncation when entries exceed maxEntries")
    void fifoTruncation() throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve("s2"));
        ResolvedSkill skill = ResolvedSkill.builder().id(1L).name("s2").skillDir(skillDir).build();

        for (int i = 0; i < 5; i++) {
            service.recordLesson(skill, null, "c" + i, "lesson " + i, 3);
        }
        String contents = Files.readString(skillDir.resolve("LESSONS.md"), StandardCharsets.UTF_8);
        long sections = contents.lines().filter(l -> l.startsWith("## ")).count();
        assertEquals(3, sections, "FIFO cap should keep only the last 3 sections");
        // Oldest two ("lesson 0" / "lesson 1") should have been dropped.
        assertFalse(contents.contains("lesson 0"));
        assertFalse(contents.contains("lesson 1"));
        assertTrue(contents.contains("lesson 4"));
    }

    @Test
    @DisplayName("clearLessons removes the file")
    void clearLessonsRemovesFile() throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve("s3"));
        ResolvedSkill skill = ResolvedSkill.builder().id(1L).name("s3").skillDir(skillDir).build();

        service.recordLesson(skill, null, null, "hello", 50);
        assertTrue(Files.exists(skillDir.resolve("LESSONS.md")));

        boolean cleared = service.clearLessons(skill);
        assertTrue(cleared);
        assertFalse(Files.exists(skillDir.resolve("LESSONS.md")));
    }

    @Test
    @DisplayName("readLessonsBody strips the canonical header")
    void readLessonsBodyStripsHeader() throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve("s4"));
        ResolvedSkill skill = ResolvedSkill.builder().id(1L).name("s4").skillDir(skillDir).build();

        service.recordLesson(skill, null, null, "needle", 50);
        String body = service.readLessonsBody(skill);
        assertNotNull(body);
        assertFalse(body.startsWith("# Lessons learned"));
        assertTrue(body.startsWith("## "));
        assertTrue(body.contains("needle"));
    }

    @Test
    @DisplayName("no workspace directory results in graceful no-op")
    void noWorkspaceNoOp() {
        ResolvedSkill skill = ResolvedSkill.builder().id(1L).name("nope").build();
        // Force a non-existent convention path so resolveWorkspace returns null.
        when(workspaceManager.resolveConventionPath("nope", null))
                .thenReturn(tempDir.resolve("does-not-exist"));
        String id = service.recordLesson(skill, null, null, "won't write", 50);
        assertNull(id);
        assertTrue(publishedEvents.isEmpty());
    }
    @Test void returnedIdIsPersistedAndOnlyThatLessonCanBeWithdrawn() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("precise"));
        var skill = ResolvedSkill.builder().id(1L).name("precise").skillDir(dir).build();
        String first = service.recordLesson(skill, null, "a", "wrong lesson", 50);
        String second = service.recordLesson(skill, null, "b", "keep lesson", 50);
        assertTrue(service.readLessons(skill).contains(first));
        var restarted = new SkillLessonsService(workspaceManager, publisher);
        assertTrue(restarted.deleteLesson(skill, first));
        assertFalse(restarted.deleteLesson(skill, first));
        String raw = restarted.readLessons(skill);
        assertFalse(raw.contains("wrong lesson"));
        assertTrue(raw.contains("keep lesson"));
        assertTrue(raw.contains(second));
        assertFalse(restarted.readLessonsBody(skill).contains("lesson-id:"));
    }
    @Test void markdownSubheadingsDoNotBecomeSeparateLessonsWhenApplyingTheCap() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("markdown"));
        var skill = ResolvedSkill.builder().id(1L).name("markdown").skillDir(dir).build();
        service.recordLesson(skill, null, null, "intro\n## Details\nkeep nested detail", 2);
        service.recordLesson(skill, null, null, "second lesson", 2);
        assertTrue(service.readLessons(skill).contains("intro"));
        assertTrue(service.readLessons(skill).contains("keep nested detail"));
    }
    @Test void concurrentAppendsDoNotLoseOrTruncateSiblings() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("concurrent"));
        var skill = ResolvedSkill.builder().id(1L).name("concurrent").skillDir(dir).build();
        var concurrent = new SkillLessonsService(workspaceManager, event -> {});
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i=0;i<20;i++) { final int n=i; tasks.add(() -> concurrent.recordLesson(skill,null,null,"unique-"+n+"!",50)); }
            for (var future : pool.invokeAll(tasks)) assertNotNull(future.get());
        }
        String raw = concurrent.readLessons(skill);
        for (int i=0;i<20;i++) assertTrue(raw.contains("unique-"+i+"!"));
    }

    @Test void withdrawingTheLastLessonLeavesNoPromptMemory() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("last"));
        var skill = ResolvedSkill.builder().id(1L).name("last").skillDir(dir).build();
        String id = service.recordLesson(skill, null, null, "remove me", 50);
        assertTrue(service.deleteLesson(skill, id));
        assertTrue(SkillLessonsService.entries(service.readLessons(skill)).isEmpty());
        assertNull(service.readLessonsBody(skill));
    }
    @Test void legacyEntryIdsAreStableAndAmbiguousDuplicatesAreRefused() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("legacy"));
        var skill = ResolvedSkill.builder().id(1L).name("legacy").skillDir(dir).build();
        String entry = "## 2026-10-01 10:00\nkeep old lesson\n";
        Path file = dir.resolve("LESSONS.md"); Files.writeString(file, "# Lessons learned for legacy\n\n" + entry);
        var id = SkillLessonsService.entries(service.readLessons(skill)).getFirst().id();
        assertTrue(id.startsWith("legacy-"));
        assertEquals(id, SkillLessonsService.entries(service.readLessons(skill)).getFirst().id());
        Files.writeString(file, "# Lessons learned for legacy\n\n" + entry + entry);
        assertFalse(SkillLessonsService.entries(service.readLessons(skill)).getFirst().deletable());
        String before = Files.readString(file);
        assertFalse(service.deleteLesson(skill, id)); assertEquals(before, Files.readString(file));
    }
    @Test void unreadableLessonsAreNeverOverwrittenByAnAssumedEmptyStore() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("unreadable"));
        var skill = ResolvedSkill.builder().id(1L).name("unreadable").skillDir(dir).build();
        Path file = dir.resolve("LESSONS.md"); byte[] bytes = new byte[]{(byte)0xc3,(byte)0x28}; Files.write(file, bytes);
        assertNull(service.recordLesson(skill,null,null,"must not overwrite",50));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertFalse(service.deleteLesson(skill,"unknown"));
    }

}
