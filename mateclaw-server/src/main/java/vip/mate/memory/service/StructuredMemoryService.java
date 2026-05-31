package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured memory service — manages typed memory entries stored as
 * workspace files (structured/user.md, structured/feedback.md, etc.).
 * <p>
 * Each file uses Markdown sections as entries:
 * <pre>
 * ## key_name
 * content text
 * > Source: agent | Updated: 2026-04-09
 * </pre>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredMemoryService {

    private static final Set<String> VALID_TYPES = Set.of("user", "feedback", "project", "reference");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^## (.+)$", Pattern.MULTILINE);

    /**
     * Stable, low-volume entry types injected unconditionally into the system prompt.
     * These describe the user and their durable preferences, so they stay relevant
     * across every turn and keep the system prefix cacheable.
     */
    private static final List<String> SYSTEM_PROMPT_TYPES = List.of("user", "feedback");

    /**
     * Growing, easily-confused entry types (specific project facts, reference notes)
     * surfaced only when the current question matches them. Always-on injection of
     * these competes with general knowledge in the prompt and causes the model to
     * confuse a specific stored fact with similarly-shaped background information.
     */
    private static final List<String> PREFETCH_TYPES = List.of("project", "reference");

    /** Maximum number of entries injected by a single query-conditioned prefetch. */
    private static final int MAX_PREFETCH_ENTRIES = 6;

    /**
     * Appended to the prefetch block header when a {@code project}-type entry is
     * included, i.e. the user's own current project was recalled for this turn.
     * Downstream prompt assembly detects this marker to avoid also injecting
     * knowledge-base reference context that would compete for "what project is
     * this" — personal project memory is authoritative over reference articles.
     */
    public static final String PROJECT_RECALLED_MARKER = "includes the user's current project";

    /** Latin word tokens of length >= 2 used for relevance shingling. */
    private static final Pattern WORD_RE = Pattern.compile("[a-z0-9]{2,}");

    /** Captures the ISO update date from an entry's metadata line ("> ... | Updated: YYYY-MM-DD"). */
    private static final Pattern UPDATED_RE = Pattern.compile("Updated:\\s*(\\d{4}-\\d{2}-\\d{2})");

    /**
     * Domain aliases bridging natural-language question terms to entry keys/types.
     * Plain substring/shingle overlap misses cross-language matches such as the
     * question term "技术栈" against the key "project_tech_stack", so each alias
     * boosts entries whose key contains one of {@code keySubstrings} or whose type
     * equals {@code type} when any of its {@code queryTerms} appears in the question.
     */
    private static final List<Alias> ALIASES = List.of(
            new Alias(List.of("代号", "项目代号", "codename", "code name"),
                    List.of("codename", "code_name", "code"), null),
            new Alias(List.of("技术栈", "技术", "技术堆栈", "tech stack", "techstack", "technology", "stack"),
                    List.of("tech", "stack", "技术"), null),
            new Alias(List.of("偏好", "风格", "习惯", "preference", "style"),
                    List.of("pref", "style", "偏好", "风格"), null),
            new Alias(List.of("项目", "project"),
                    List.of(), "project")
    );

    /** A natural-language-to-entry alias rule used by relevance scoring. */
    private record Alias(List<String> queryTerms, List<String> keySubstrings, String type) {
        boolean matchesQuery(String query) {
            return queryTerms.stream().anyMatch(query::contains);
        }

        boolean matchesEntry(String entryType, String keyLower) {
            boolean keyHit = keySubstrings.stream().anyMatch(keyLower::contains);
            boolean typeHit = type != null && type.equals(entryType);
            return keyHit || typeHit;
        }
    }

    /** A structured entry with its relevance score and update date for the current query. */
    private record ScoredEntry(String type, String key, String body, int score, String updated) {}

    private final WorkspaceFileService workspaceFileService;
    private final ApplicationEventPublisher eventPublisher;

    /** Per-file lock to prevent concurrent read-modify-write on the same file */
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * Store a typed memory entry. Creates or updates the section with the given key.
     * Uses per-file locking to handle concurrent tool calls writing to the same file.
     */
    public void remember(Long agentId, String type, String key, String content, String source) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename);

            String metadata = "> Source: " + (source != null ? source : "agent")
                    + " | Updated: " + LocalDate.now();
            String newSection = "## " + key + "\n" + content.trim() + "\n" + metadata;

            // Check if section already exists → replace
            String existingSection = findSection(fileContent, key);
            String updated;
            if (existingSection != null) {
                updated = fileContent.replace(existingSection, newSection);
            } else {
                // Append new section
                updated = fileContent.isBlank() ? newSection : fileContent.trim() + "\n\n" + newSection;
            }

            workspaceFileService.saveFile(agentId, filename, updated);
            log.info("[StructuredMemory] {} entry '{}' for agent={} (source={})",
                    existingSection != null ? "Updated" : "Added", key, agentId, source);
            // Publish event for SOUL auto-evolution (Phase 2)
            eventPublisher.publishEvent(new MemoryWriteEvent(agentId, filename, "remember", content));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Search entries by type and optional keyword.
     */
    public List<Map<String, String>> recall(Long agentId, String type, String keyword) {
        if (type != null) {
            validateType(type);
        }

        List<String> types = type != null ? List.of(type) : List.copyOf(VALID_TYPES);
        List<Map<String, String>> results = new ArrayList<>();

        for (String t : types) {
            String fileContent = readFileSafe(agentId, toFilename(t));
            if (fileContent.isBlank()) continue;

            Map<String, String> sections = parseSections(fileContent);
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                if (keyword == null || keyword.isBlank()
                        || entry.getKey().toLowerCase().contains(keyword.toLowerCase())
                        || entry.getValue().toLowerCase().contains(keyword.toLowerCase())) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("type", t);
                    item.put("key", entry.getKey());
                    item.put("content", entry.getValue());
                    results.add(item);
                }
            }
        }
        return results;
    }

    /**
     * Remove a memory entry by type and key.
     */
    public boolean forget(Long agentId, String type, String key) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename);
            if (fileContent.isBlank()) return false;

            String section = findSection(fileContent, key);
            if (section == null) return false;

            String updated = fileContent.replace(section, "").trim();
            // Clean up double blank lines
            updated = updated.replaceAll("\n{3,}", "\n\n");
            workspaceFileService.saveFile(agentId, filename, updated);
            log.info("[StructuredMemory] Removed entry '{}' (type={}) for agent={}", key, type, agentId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * List all entries of a given type.
     */
    public List<Map<String, String>> listEntries(Long agentId, String type) {
        return recall(agentId, type, null);
    }

    /**
     * Build a formatted memory block for system prompt injection.
     * Includes only the stable, low-volume entry types ({@link #SYSTEM_PROMPT_TYPES});
     * growing/specific types are surfaced per-turn via {@link #buildPrefetchBlock}.
     */
    public String buildMemoryBlock(Long agentId) {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        for (String type : SYSTEM_PROMPT_TYPES) {
            String fileContent = readFileSafe(agentId, toFilename(type));
            if (fileContent.isBlank()) continue;

            Map<String, String> sections = parseSections(fileContent);
            if (sections.isEmpty()) continue;

            if (!hasContent) {
                sb.append("## Structured Memory\n\n");
                hasContent = true;
            }

            sb.append("### ").append(typeDisplayName(type)).append("\n");
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                // Extract just the content line (skip metadata)
                String content = extractContentOnly(entry.getValue());
                sb.append("- **").append(entry.getKey()).append("**: ").append(content).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Build a query-conditioned memory block for per-turn prefetch injection.
     * Scores {@link #PREFETCH_TYPES} entries against the user's question and returns
     * the top matches as Markdown, or an empty string when nothing is relevant.
     * Keeping these entries out of the always-on system prompt avoids salience
     * competition that would otherwise let the model answer from general knowledge
     * instead of the specific stored fact.
     */
    public String buildPrefetchBlock(Long agentId, String userQuery) {
        if (userQuery == null || userQuery.isBlank()) return "";

        List<ScoredEntry> scored = recallRelevant(agentId, userQuery, PREFETCH_TYPES, MAX_PREFETCH_ENTRIES);
        if (scored.isEmpty()) return "";

        boolean hasProject = scored.stream().anyMatch(e -> "project".equals(e.type()));
        StringBuilder sb = new StringBuilder("## Relevant Structured Memory");
        if (hasProject) {
            sb.append(" (").append(PROJECT_RECALLED_MARKER).append(")");
        }
        sb.append("\n");
        for (ScoredEntry e : scored) {
            sb.append("- **").append(e.key()).append("**: ")
                    .append(extractContentOnly(e.body()));
            if (!e.updated().isBlank()) {
                sb.append(" _(updated ").append(e.updated()).append(")_");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== Internal ====================

    /**
     * Score entries of the given types against the user query and return the
     * highest-scoring matches (score &gt; 0), best first, capped at {@code limit}.
     */
    private List<ScoredEntry> recallRelevant(Long agentId, String userQuery, List<String> types, int limit) {
        String q = userQuery.toLowerCase();
        Set<String> queryShingles = shingles(q);

        List<ScoredEntry> matches = new ArrayList<>();
        for (String t : types) {
            String fileContent = readFileSafe(agentId, toFilename(t));
            if (fileContent.isBlank()) continue;

            for (Map.Entry<String, String> entry : parseSections(fileContent).entrySet()) {
                int score = scoreEntry(q, queryShingles, t, entry.getKey(), entry.getValue());
                if (score > 0) {
                    matches.add(new ScoredEntry(t, entry.getKey(), entry.getValue(),
                            score, extractUpdated(entry.getValue())));
                }
            }
        }

        // Most relevant first; break ties by recency so the freshest fact wins a conflict.
        matches.sort(Comparator.comparingInt(ScoredEntry::score).reversed()
                .thenComparing(Comparator.comparing(ScoredEntry::updated).reversed()));
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    /**
     * Combine three lightweight relevance signals into a single score:
     * key-token presence in the query, domain-alias boosts, and character-level
     * shingle overlap (CJK bigrams + Latin word tokens) between the query and entry.
     */
    private int scoreEntry(String query, Set<String> queryShingles, String type, String key, String body) {
        int score = 0;
        String keyLower = key.toLowerCase();

        // 1. Key tokens appearing verbatim in the query.
        for (String token : keyLower.split("[_\\s-]+")) {
            if (token.length() >= 2 && query.contains(token)) score += 4;
        }

        // 2. Domain-alias boosts for cross-language question/key matches.
        for (Alias alias : ALIASES) {
            if (alias.matchesQuery(query) && alias.matchesEntry(type, keyLower)) score += 6;
        }

        // 3. Shingle overlap between the query and the entry text (capped).
        Set<String> entryShingles = shingles((key + " " + body).toLowerCase());
        int overlap = 0;
        for (String s : entryShingles) {
            if (queryShingles.contains(s)) overlap++;
        }
        score += Math.min(overlap, 6);

        return score;
    }

    /**
     * Produce a language-agnostic shingle set: Latin word tokens (length &gt;= 2)
     * plus CJK character bigrams (single CJK characters when isolated). This lets
     * relevance scoring work without a word segmenter on space-free CJK text.
     */
    private static Set<String> shingles(String text) {
        Set<String> out = new HashSet<>();

        Matcher m = WORD_RE.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }

        for (String run : text.replaceAll("[^\\p{IsHan}]", " ").split("\\s+")) {
            if (run.isEmpty()) continue;
            if (run.length() == 1) {
                out.add(run);
            } else {
                for (int i = 0; i + 2 <= run.length(); i++) {
                    out.add(run.substring(i, i + 2));
                }
            }
        }

        return out;
    }

    private String toFilename(String type) {
        return "structured/" + type + ".md";
    }

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("Invalid memory type: " + type
                    + ". Must be one of: " + VALID_TYPES);
        }
    }

    private String readFileSafe(Long agentId, String filename) {
        try {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parse all sections from a Markdown file.
     * Returns map of key → full section content (including metadata line).
     */
    private Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher matcher = SECTION_PATTERN.matcher(content);
        List<int[]> positions = new ArrayList<>();
        List<String> keys = new ArrayList<>();

        while (matcher.find()) {
            positions.add(new int[]{matcher.start(), matcher.end()});
            keys.add(matcher.group(1).trim());
        }

        for (int i = 0; i < positions.size(); i++) {
            int bodyStart = positions.get(i)[1] + 1; // skip newline after header
            int bodyEnd = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();
            sections.put(keys.get(i), body);
        }

        return sections;
    }

    /**
     * Find a complete section by key (header + body), or null if not found.
     */
    private String findSection(String content, String key) {
        String header = "## " + key;
        int idx = content.indexOf(header);
        if (idx < 0) return null;

        // Find the end: next ## header or EOF
        int nextSection = content.indexOf("\n## ", idx + header.length());
        int end = nextSection >= 0 ? nextSection : content.length();
        return content.substring(idx, end).trim();
    }

    /**
     * Extract just the content text, stripping metadata lines (starting with >).
     */
    private String extractContentOnly(String sectionBody) {
        StringBuilder sb = new StringBuilder();
        for (String line : sectionBody.split("\n")) {
            if (!line.startsWith(">") && !line.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(line.trim());
            }
        }
        return sb.toString();
    }

    /** Extract the ISO update date from an entry body's metadata line, or "" if absent. */
    private String extractUpdated(String sectionBody) {
        Matcher m = UPDATED_RE.matcher(sectionBody);
        return m.find() ? m.group(1) : "";
    }

    private String typeDisplayName(String type) {
        return switch (type) {
            case "user" -> "User Profile";
            case "feedback" -> "Feedback";
            case "project" -> "Project";
            case "reference" -> "Reference";
            default -> type;
        };
    }
}
