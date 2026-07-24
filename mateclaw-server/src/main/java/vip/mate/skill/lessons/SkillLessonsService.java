package vip.mate.skill.lessons;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.skill.lessons.event.SkillLessonWrittenEvent;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * RFC-090 §11.4 / §14.3 — per-skill {@code LESSONS.md} read & write.
 *
 * <p>SKILL.md is a deployment artifact and must remain read-only at
 * runtime. Lessons captured by the skill itself land in a sibling file
 * {@code LESSONS.md} so the system prompt enhancement layer can append
 * recent experience without rewriting the manifest.
 *
 * <p>Why a dedicated file rather than {@code MEMORY.md}:
 * <ul>
 *   <li>Lessons are scoped to <i>this</i> skill — the agent must not
 *       leak skill-specific tactics into another skill's context.</li>
 *   <li>Agents share canonical memory, but a single skill may belong
 *       to multiple agents; each agent's MEMORY.md is global.</li>
 *   <li>Per §14.3, mixing lessons into {@code MemoryWriteEvent} would
 *       inflate the SOUL summarizer's recompute counter.</li>
 * </ul>
 *
 * <p>Storage format (markdown, append-only, FIFO truncated):
 * <pre>
 * # Lessons learned for {skill-id}
 *
 * ## 2026-04-30 14:23  (conversation: 7a3b...)
 * Lesson body
 * Source: {skill-id} turn #N
 *
 * ## 2026-04-29 09:12  (conversation: 9c4d...)
 * ...
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLessonsService {

    /** Cap entries to keep LESSONS.md from ballooning (§10.1 risk 3). */
    private static final int DEFAULT_MAX_ENTRIES = 50;

    private static final DateTimeFormatter HEADING_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withLocale(Locale.ROOT);

    private final SkillWorkspaceManager workspaceManager;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Append a lesson to {@code LESSONS.md}.
     *
     * <p>Side effects:
     * <ol>
     *   <li>Creates the file if it doesn't exist (with the canonical
     *       header).</li>
     *   <li>Appends a new {@code ## yyyy-MM-dd HH:mm (conversation: id)}
     *       section with the lesson body.</li>
     *   <li>If total entries exceed {@code maxEntries}, drops the oldest
     *       sections (FIFO truncation).</li>
     *   <li>Publishes {@link SkillLessonWrittenEvent} on success.</li>
     * </ol>
     *
     * @param resolved      the resolved skill (must have a workspace
     *                      directory for the write to succeed; database-
     *                      only skills are returned as a no-op)
     * @param agentId       agent that produced the lesson, may be null
     * @param conversationId conversation context, may be null
     * @param content       lesson text — caller's responsibility to
     *                      sanitize / truncate
     * @param maxEntries    upper bound on entries; non-positive falls
     *                      back to {@link #DEFAULT_MAX_ENTRIES}
     * @return new lesson id (UUID) when persisted, null on no-op
     */
    public String recordLesson(ResolvedSkill resolved, Long agentId,
                                String conversationId, String content,
                                int maxEntries) {
        if (resolved == null || resolved.getName() == null) {
            log.debug("recordLesson: missing skill identity, skipping");
            return null;
        }
        if (content == null || content.isBlank()) {
            log.debug("recordLesson: empty content for skill '{}', skipping", resolved.getName());
            return null;
        }
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) {
            log.debug("recordLesson: no workspace directory for skill '{}', skipping",
                    resolved.getName());
            return null;
        }

        Path lessonsFile = workspace.resolve("LESSONS.md");
        int cap = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        String lessonId = UUID.randomUUID().toString();

        try {
            ensureFileWithHeader(lessonsFile, resolved.getName());
            String section = buildSection(conversationId, content);
            Files.writeString(lessonsFile, section,
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            enforceCap(lessonsFile, resolved.getName(), cap);
        } catch (IOException e) {
            log.warn("Failed to write LESSONS.md for skill '{}': {}",
                    resolved.getName(), e.getMessage());
            return null;
        }

        eventPublisher.publishEvent(new SkillLessonWrittenEvent(
                agentId,
                resolved.getId(),
                resolved.getName(),
                conversationId,
                content));
        return lessonId;
    }

    /**
     * Read raw {@code LESSONS.md} contents. Returns null when the file
     * is missing — callers should treat as "no lessons yet" without
     * branching on exception.
     */
    public String readLessons(ResolvedSkill resolved) {
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) return null;
        Path file = workspace.resolve("LESSONS.md");
        if (!Files.exists(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read LESSONS.md for skill '{}': {}",
                    resolved.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Convenience for the prompt enhancement layer: returns the body
     * (everything after the canonical title) so it can be appended to
     * a skill's SKILL.md body without duplicating the title.
     */
    public String readLessonsBody(ResolvedSkill resolved) {
        String full = readLessons(resolved);
        if (full == null || full.isBlank()) return null;
        // Strip the leading "# Lessons learned for {name}\n\n" header.
        int firstSection = full.indexOf("\n## ");
        if (firstSection < 0) return full.trim();
        return full.substring(firstSection + 1).trim();
    }

    /**
     * Delete a single lesson by id by rewriting the file without the
     * matching section. Returns {@code true} when an entry was actually
     * removed.
     */
    public boolean deleteLesson(ResolvedSkill resolved, String lessonId) {
        // Lessons today have no per-section id — id is generated on
        // write but not persisted. This is a placeholder for the API
        // wiring; callers that need precise revert should use
        // clearLessons or rebuild via re-recording. Returning false
        // signals "no-op".
        return false;
    }

    /**
     * Wipe the LESSONS.md file entirely (called by the
     * {@code DELETE /skills/{id}/lessons/clear} admin endpoint).
     */
    public boolean clearLessons(ResolvedSkill resolved) {
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) return false;
        Path file = workspace.resolve("LESSONS.md");
        if (!Files.exists(file)) return false;
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            log.warn("Failed to clear LESSONS.md for skill '{}': {}",
                    resolved.getName(), e.getMessage());
            return false;
        }
    }

    // ==================== internals ====================

    private Path resolveWorkspace(ResolvedSkill resolved) {
        if (resolved == null || resolved.getName() == null) return null;
        if (resolved.getSkillDir() != null) return resolved.getSkillDir();
        Path convention = workspaceManager.resolveConventionPath(resolved.getName(), resolved.getWorkspaceId());
        return Files.exists(convention) && Files.isDirectory(convention) ? convention : null;
    }

    private void ensureFileWithHeader(Path file, String skillName) throws IOException {
        if (Files.exists(file)) return;
        String header = "# Lessons learned for " + skillName + "\n\n";
        Files.writeString(file, header, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private String buildSection(String conversationId, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## ").append(LocalDateTime.now().format(HEADING_TS));
        if (conversationId != null && !conversationId.isBlank()) {
            sb.append("  (conversation: ").append(conversationId).append(")");
        }
        sb.append("\n").append(content.trim()).append("\n");
        return sb.toString();
    }

    /**
     * FIFO truncation. Reads the file once, splits on {@code "\n## "},
     * keeps the title + last {@code maxEntries} sections, and rewrites.
     * Cheap enough for the typical N=50 cap.
     */
    private void enforceCap(Path file, String skillName, int maxEntries) throws IOException {
        if (maxEntries <= 0) return;
        String full = Files.readString(file, StandardCharsets.UTF_8);
        if (full.isBlank()) return;

        String header = "# Lessons learned for " + skillName + "\n";
        int firstSectionIdx = full.indexOf("\n## ");
        if (firstSectionIdx < 0) return; // no sections yet

        String preface = full.substring(0, firstSectionIdx);
        String sectionsBlob = full.substring(firstSectionIdx + 1); // strip the leading newline

        String[] sections = sectionsBlob.split("\n## ");
        if (sections.length <= maxEntries) return;

        List<String> kept = new ArrayList<>(maxEntries);
        for (int i = sections.length - maxEntries; i < sections.length; i++) {
            kept.add(sections[i]);
        }
        StringBuilder rebuilt = new StringBuilder();
        if (!preface.isBlank()) rebuilt.append(preface).append("\n");
        else rebuilt.append(header).append("\n");
        for (int i = 0; i < kept.size(); i++) {
            rebuilt.append("## ").append(kept.get(i));
            if (i < kept.size() - 1 && !kept.get(i).endsWith("\n")) rebuilt.append("\n");
        }
        Files.writeString(file, rebuilt.toString(), StandardCharsets.UTF_8);
    }

    /** Test hook (package-private) — exposes the parsing rule used by readLessonsBody. */
    static List<String> splitSections(String full) {
        if (full == null || full.isBlank()) return Collections.emptyList();
        int firstSectionIdx = full.indexOf("\n## ");
        if (firstSectionIdx < 0) return Collections.emptyList();
        String body = full.substring(firstSectionIdx + 1);
        return java.util.Arrays.asList(body.split("\n## "));
    }
}
