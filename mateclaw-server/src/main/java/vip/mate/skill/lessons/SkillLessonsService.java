package vip.mate.skill.lessons;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.skill.lessons.event.SkillLessonWrittenEvent;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Skill-local learning stays separate from canonical memory and can be withdrawn precisely. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLessonsService {
    private static final int DEFAULT_MAX_ENTRIES = 50;
    private static final DateTimeFormatter HEADING_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final Pattern HEADING = Pattern.compile("(?m)^## (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}[^\\r\\n]*)(?:\\r?\\n|$)");
    private static final Pattern ID_MARKER = Pattern.compile("(?m)^<!-- lesson-id: ([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}) -->\\r?\\n?");
    private final SkillWorkspaceManager workspaceManager;
    private final ApplicationEventPublisher eventPublisher;
    // Bounded JVM locks serialize append, cap, withdrawal and clear for the same file.
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public record LessonEntry(String id, String heading, String content, boolean deletable) {}
    private record Section(int start, int end, String id, String heading, String content) {}

    public String recordLesson(ResolvedSkill resolved, Long agentId, String conversationId, String content, int maxEntries) {
        if (resolved == null || resolved.getName() == null || content == null || content.isBlank()) return null;
        // Reserved record headings in a body would make its storage boundaries ambiguous.
        if (HEADING.matcher(content).find()) return null;
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) return null;
        Path file = workspace.resolve("LESSONS.md");
        String id = UUID.randomUUID().toString();
        synchronized (lock(file)) {
            try {
                String existing = readExisting(file);
                if (existing == null) existing = "# Lessons learned for " + resolved.getName() + "\n\n";
                String conversation = conversationId == null ? "" : conversationId.replaceAll("[\\r\\n]", " ").trim();
                String section = "\n## " + LocalDateTime.now().format(HEADING_TS)
                        + (conversation.isEmpty() ? "" : "  (conversation: " + conversation + ")")
                        + "\n<!-- lesson-id: " + id + " -->\n" + content.trim() + "\n";
                String updated = existing + (existing.endsWith("\n") ? "" : "\n") + section;
                List<Section> all = parse(updated);
                int cap = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
                if (all.size() > cap) updated = updated.substring(0, all.getFirst().start())
                        + updated.substring(all.get(all.size() - cap).start());
                atomicWrite(file, updated);
            } catch (IOException e) {
                log.warn("Failed to write lessons for '{}': {}", resolved.getName(), e.getMessage());
                return null;
            }
        }
        eventPublisher.publishEvent(new SkillLessonWrittenEvent(agentId, resolved.getId(), resolved.getName(), conversationId, content));
        return id;
    }

    public String readLessons(ResolvedSkill resolved) {
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) return null;
        try { return readExisting(workspace.resolve("LESSONS.md")); }
        catch (IOException e) { log.warn("Failed to read lessons for '{}': {}", resolved.getName(), e.getMessage()); return null; }
    }

    public String readLessonsBody(ResolvedSkill resolved) {
        String full = readLessons(resolved);
        if (full == null || full.isBlank()) return null;
        var matcher = HEADING.matcher(full);
        boolean found = matcher.find();
        if (!found && full.trim().equals("# Lessons learned for " + resolved.getName())) return null;
        String body = found ? full.substring(matcher.start()) : full;
        return ID_MARKER.matcher(body).replaceAll("").trim();
    }

    /** Build the UI projection from the same raw snapshot returned by the read endpoint. */
    public static List<LessonEntry> entries(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Section> sections = parse(raw);
        Map<String, Integer> counts = new HashMap<>();
        sections.forEach(s -> counts.merge(s.id(), 1, Integer::sum));
        return sections.stream().map(s -> new LessonEntry(s.id(), s.heading(), s.content(), counts.get(s.id()) == 1)).toList();
    }

    public boolean deleteLesson(ResolvedSkill resolved, String lessonId) {
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null || lessonId == null || lessonId.isBlank()) return false;
        Path file = workspace.resolve("LESSONS.md");
        synchronized (lock(file)) {
            try {
                String full = readExisting(file);
                if (full == null) return false;
                List<Section> matches = parse(full).stream().filter(s -> s.id().equals(lessonId)).toList();
                if (matches.size() != 1) return false;
                Section target = matches.getFirst();
                atomicWrite(file, full.substring(0, target.start()) + full.substring(target.end()));
                return true;
            } catch (IOException e) { log.warn("Failed to withdraw lesson for '{}': {}", resolved.getName(), e.getMessage()); return false; }
        }
    }

    public boolean clearLessons(ResolvedSkill resolved) {
        Path workspace = resolveWorkspace(resolved);
        if (workspace == null) return false;
        Path file = workspace.resolve("LESSONS.md");
        synchronized (lock(file)) {
            try {
                if (Files.isSymbolicLink(file)) return false;
                return Files.deleteIfExists(file);
            } catch (IOException e) { log.warn("Failed to clear lessons for '{}': {}", resolved.getName(), e.getMessage()); return false; }
        }
    }

    private Object lock(Path file) { return locks[Math.floorMod(file.toAbsolutePath().normalize().hashCode(), locks.length)]; }

    private Path resolveWorkspace(ResolvedSkill resolved) {
        if (resolved == null || resolved.getName() == null) return null;
        if (resolved.getSkillDir() != null) return resolved.getSkillDir();
        Path path = workspaceManager.resolveConventionPath(resolved.getName(), resolved.getWorkspaceId());
        return Files.isDirectory(path) ? path : null;
    }

    private static String readExisting(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Lessons file must not be a symbolic link");
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (NoSuchFileException e) { return null; }
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".lessons-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static List<Section> parse(String full) {
        var matcher = HEADING.matcher(full);
        List<int[]> positions = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (matcher.find()) { positions.add(new int[]{matcher.start(), matcher.end()}); headings.add(matcher.group(1)); }
        List<Section> sections = new ArrayList<>();
        for (int i=0;i<positions.size();i++) {
            int start = positions.get(i)[0], bodyStart = positions.get(i)[1];
            int end = i + 1 < positions.size() ? positions.get(i+1)[0] : full.length();
            String body = full.substring(bodyStart, end);
            var marker = ID_MARKER.matcher(body);
            String id = marker.find() && marker.start() == 0 ? marker.group(1) : legacyId(full.substring(start, end).strip());
            sections.add(new Section(start, end, id, headings.get(i), ID_MARKER.matcher(body).replaceAll("").trim()));
        }
        return sections;
    }

    private static String legacyId(String section) {
        try { return "legacy-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(section.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static List<String> splitSections(String full) {
        if (full == null) return List.of();
        return parse(full).stream().map(s -> full.substring(s.start(), s.end())).toList();
    }
}
