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
    private final vip.mate.memory.MemoryProperties properties;

    /** Per-file lock to prevent concurrent read-modify-write on the same file */
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * Store a typed memory entry. Creates or updates the section with the given key.
     * Uses per-file locking to handle concurrent tool calls writing to the same file.
     */
    public void remember(Long agentId, String type, String key, String content, String source) {
        remember(agentId, type, key, content, source, null);
    }

    /** Owner-scoped variant of {@link #remember}. */
    public void remember(Long agentId, String type, String key, String content, String source, String ownerKey) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + (ownerKey == null ? "" : ownerKey) + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename, ownerKey);

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

            saveStructured(agentId, filename, updated, ownerKey);
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
        return recall(agentId, type, keyword, null);
    }

    /** Owner-scoped variant of {@link #recall(Long, String, String)}. */
    public List<Map<String, String>> recall(Long agentId, String type, String keyword, String ownerKey) {
        if (type != null) {
            validateType(type);
        }

        List<String> types = type != null ? List.of(type) : List.copyOf(VALID_TYPES);
        List<Map<String, String>> results = new ArrayList<>();

        for (String t : types) {
            String fileContent = readFileSafe(agentId, toFilename(t), ownerKey);
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
        return forget(agentId, type, key, null);
    }

    /** Owner-scoped variant of {@link #forget(Long, String, String)}. */
    public boolean forget(Long agentId, String type, String key, String ownerKey) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + (ownerKey == null ? "" : ownerKey) + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename, ownerKey);
            if (fileContent.isBlank()) return false;

            String section = findSection(fileContent, key);
            if (section == null) return false;

            String updated = fileContent.replace(section, "").trim();
            // Clean up double blank lines
            updated = updated.replaceAll("\n{3,}", "\n\n");
            saveStructured(agentId, filename, updated, ownerKey);
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

    /** Owner-scoped variant of {@link #listEntries(Long, String)}. */
    public List<Map<String, String>> listEntries(Long agentId, String type, String ownerKey) {
        return recall(agentId, type, null, ownerKey);
    }

    /**
     * Build a formatted memory block for system prompt injection.
     * Includes only the stable, low-volume entry types ({@link #SYSTEM_PROMPT_TYPES});
     * growing/specific types are surfaced per-turn via {@link #buildPrefetchBlock}.
     */
    public String buildMemoryBlock(Long agentId) {
        return buildMemoryBlock(agentId, null);
    }

    /** Reserve left for structural overhead (headers, blank lines) when truncating a single oversized entry. */
    private static final int BLOCK_STRUCTURE_RESERVE = 120;

    /** Owner-scoped variant of {@link #buildMemoryBlock(Long)}. */
    public String buildMemoryBlock(Long agentId, String ownerKey) {
        int maxChars = Math.max(0, properties.getSystemBlockMaxChars());
        int maxPerType = Math.max(0, properties.getSystemBlockMaxEntriesPerType());

        // 1. Collect every always-on entry with its update date and a global
        //    insertion index (file order, types in SYSTEM_PROMPT_TYPES order).
        //    Truncate any single entry larger than the whole budget so it can
        //    never blow the cap on its own.
        int contentCap = maxChars > 0 ? Math.max(1, maxChars - BLOCK_STRUCTURE_RESERVE) : -1;
        List<BlockEntry> all = new ArrayList<>();
        int globalIndex = 0;
        for (String type : SYSTEM_PROMPT_TYPES) {
            String fileContent = readFileSafe(agentId, toFilename(type), ownerKey);
            if (fileContent.isBlank()) continue;
            for (Map.Entry<String, String> entry : parseSections(fileContent).entrySet()) {
                String content = extractContentOnly(entry.getValue());
                if (content.isBlank()) continue;
                if (contentCap >= 0 && content.length() > contentCap) {
                    content = content.substring(0, contentCap) + "…";
                }
                all.add(new BlockEntry(type, entry.getKey(), content,
                        extractUpdated(entry.getValue()), globalIndex++));
            }
        }
        if (all.isEmpty()) return "";

        // 2. Enforce the always-on budget against the TRUE rendered length
        //    (headers, blank lines, and the omission note all counted), keeping
        //    the most-recently-updated entries so accumulated memory cannot grow
        //    the per-turn context without bound.
        Set<BlockEntry> kept = selectWithinBudget(all, maxChars, maxPerType);
        int omitted = all.size() - kept.size();
        return renderBlock(all, kept, omitted);
    }

    /** A candidate entry for the always-on block, with budget metadata. */
    private record BlockEntry(String type, String key, String content,
                              String updated, int index) {}

    /**
     * Render the always-on block: survivors grouped by type, in original file
     * order (stable ordering keeps the system prefix cacheable), followed by an
     * omission note when entries were dropped.
     */
    private String renderBlock(List<BlockEntry> all, Set<BlockEntry> kept, int omitted) {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;
        for (String type : SYSTEM_PROMPT_TYPES) {
            List<BlockEntry> typeEntries = all.stream()
                    .filter(e -> e.type().equals(type) && kept.contains(e))
                    .sorted(Comparator.comparingInt(BlockEntry::index))
                    .toList();
            if (typeEntries.isEmpty()) continue;

            if (!hasContent) {
                sb.append("## Structured Memory\n\n");
                hasContent = true;
            }
            sb.append("### ").append(typeDisplayName(type)).append("\n");
            for (BlockEntry e : typeEntries) {
                sb.append("- **").append(e.key()).append("**: ").append(e.content()).append("\n");
            }
            sb.append("\n");
        }
        if (omitted > 0) {
            sb.append("> ").append(omitted)
              .append(" older memory entries omitted to bound context size.\n");
        }
        return sb.toString().trim();
    }

    /**
     * Select the entries that fit the always-on injection budget, preferring the
     * most-recently-updated ones. Applies a per-type entry cap first, then a
     * global character budget measured against the actual rendered block (not
     * just bullet lengths). Newer entries (later update date, then later
     * insertion order) win; ties and missing dates fall back to insertion order.
     */
    private Set<BlockEntry> selectWithinBudget(List<BlockEntry> all, int maxChars, int maxPerType) {
        // Keep-priority: most recent update first, then most recently inserted.
        Comparator<BlockEntry> newestFirst = Comparator
                .comparing(BlockEntry::updated, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(BlockEntry::index)
                .reversed();

        // Per-type cap: drop the oldest entries beyond the cap.
        List<BlockEntry> survivors = new ArrayList<>(all);
        if (maxPerType > 0) {
            Set<BlockEntry> overflow = new HashSet<>();
            for (String type : SYSTEM_PROMPT_TYPES) {
                List<BlockEntry> ofType = survivors.stream()
                        .filter(e -> e.type().equals(type))
                        .sorted(newestFirst)
                        .toList();
                if (ofType.size() > maxPerType) {
                    overflow.addAll(ofType.subList(maxPerType, ofType.size()));
                }
            }
            survivors.removeAll(overflow);
        }

        if (maxChars <= 0) {
            return new HashSet<>(survivors);
        }

        // Global character budget: admit newest entries while the fully rendered
        // block stays within budget. Measuring the real render (including the
        // omission note) makes the cap exact; once an entry no longer fits, every
        // remaining entry is older and is dropped too.
        List<BlockEntry> ordered = survivors.stream().sorted(newestFirst).toList();
        Set<BlockEntry> picked = new HashSet<>();
        for (BlockEntry e : ordered) {
            Set<BlockEntry> trial = new HashSet<>(picked);
            trial.add(e);
            int omittedIfStop = all.size() - trial.size();
            if (renderBlock(all, trial, Math.max(0, omittedIfStop)).length() > maxChars) {
                break;
            }
            picked.add(e);
        }
        return picked;
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
        return buildPrefetchBlock(agentId, userQuery, null);
    }

    /** Owner-scoped variant of {@link #buildPrefetchBlock(Long, String)}. */
    public String buildPrefetchBlock(Long agentId, String userQuery, String ownerKey) {
        if (userQuery == null || userQuery.isBlank()) return "";

        List<ScoredEntry> scored = recallRelevant(agentId, userQuery, PREFETCH_TYPES, MAX_PREFETCH_ENTRIES, ownerKey);
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
        return recallRelevant(agentId, userQuery, types, limit, null);
    }

    private List<ScoredEntry> recallRelevant(Long agentId, String userQuery, List<String> types, int limit, String ownerKey) {
        String q = userQuery.toLowerCase();
        Set<String> queryShingles = shingles(q);

        List<ScoredEntry> matches = new ArrayList<>();
        for (String t : types) {
            String fileContent = readFileSafe(agentId, toFilename(t), ownerKey);
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

    // ==================== Consolidation support ====================

    /** The always-on structured types injected into every system prompt. */
    public List<String> alwaysOnTypes() {
        return SYSTEM_PROMPT_TYPES;
    }

    /** Read the raw Markdown of a structured type file (owner-scoped when personal). */
    public String readTypeRaw(Long agentId, String type, String ownerKey) {
        validateType(type);
        return readFileSafe(agentId, toFilename(type), ownerKey);
    }

    /** Count the {@code ## key} entries in a structured file's raw Markdown. */
    public int countEntries(String rawContent) {
        return (rawContent == null || rawContent.isBlank()) ? 0 : parseSections(rawContent).size();
    }

    /**
     * Distinct buckets that hold entries for a structured type and are eligible
     * for consolidation: the shared bucket (returned as {@code null}) plus each
     * personal owner that has its own row. Lets the nightly maintenance pass
     * consolidate per-owner memory, where most growth actually accumulates.
     */
    public List<String> consolidatableOwnerKeys(Long agentId, String type) {
        validateType(type);
        String filename = toFilename(type);
        List<String> owners = new ArrayList<>();
        owners.add(null); // shared (TEAM/GLOBAL) bucket
        for (WorkspaceFileEntity f : workspaceFileService.listFiles(agentId)) {
            if (filename.equals(f.getFilename()) && isPersonal(f.getOwnerKey())
                    && !owners.contains(f.getOwnerKey())) {
                owners.add(f.getOwnerKey());
            }
        }
        return owners;
    }

    /**
     * Atomically replace all entries of a structured type with a consolidated set,
     * re-serialized in the canonical {@code ## key / content / > Source | Updated}
     * format. Used by the nightly consolidation pass to shrink always-on memory.
     * Insertion order of {@code entries} is preserved.
     * <p>
     * Update dates are preserved per key: an entry whose key already existed keeps
     * its original {@code Updated} date, and a newly-merged key inherits the newest
     * date among the existing entries. This keeps recency/LRU semantics intact —
     * consolidation must not make a batch of old facts look freshly written.
     */
    public void replaceTypeEntries(Long agentId, String type, String ownerKey,
                                   LinkedHashMap<String, String> entries, String source) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + (ownerKey == null ? "" : ownerKey) + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // Derive prior update dates so consolidation preserves provenance.
            Map<String, String> keyToDate = new HashMap<>();
            String newestDate = "";
            for (Map.Entry<String, String> s : parseSections(readFileSafe(agentId, filename, ownerKey)).entrySet()) {
                String d = extractUpdated(s.getValue());
                if (!d.isEmpty()) {
                    keyToDate.put(s.getKey(), d);
                    if (d.compareTo(newestDate) > 0) newestDate = d;
                }
            }
            String fallbackDate = newestDate.isEmpty() ? LocalDate.now().toString() : newestDate;
            String src = source != null ? source : "consolidation";

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()
                        || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                String key = e.getKey().trim();
                String date = keyToDate.getOrDefault(key, fallbackDate);
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("## ").append(key).append("\n")
                  .append(e.getValue().trim())
                  .append("\n> Source: ").append(src).append(" | Updated: ").append(date);
            }
            saveStructured(agentId, filename, sb.toString(), ownerKey);
            log.info("[StructuredMemory] Replaced {} entries in '{}' for agent={} owner={} (source={})",
                    entries.size(), filename, agentId, ownerKey, src);
        } finally {
            lock.unlock();
        }
    }

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("Invalid memory type: " + type
                    + ". Must be one of: " + VALID_TYPES);
        }
    }

    private String readFileSafe(Long agentId, String filename) {
        return readFileSafe(agentId, filename, null);
    }

    private String readFileSafe(Long agentId, String filename, String ownerKey) {
        try {
            WorkspaceFileEntity file = isPersonal(ownerKey)
                    ? workspaceFileService.getMemoryFile(agentId, filename, ownerKey)
                    : workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Persist structured memory to the owner's PERSONAL bucket, or shared when no real owner. */
    private void saveStructured(Long agentId, String filename, String content, String ownerKey) {
        if (isPersonal(ownerKey)) {
            workspaceFileService.saveMemoryFile(agentId, filename, content, ownerKey);
        } else {
            workspaceFileService.saveFile(agentId, filename, content);
        }
    }

    /** A real, isolatable owner — not null/blank and not the system bucket. */
    private boolean isPersonal(String ownerKey) {
        return ownerKey != null && !ownerKey.isBlank()
                && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey);
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
