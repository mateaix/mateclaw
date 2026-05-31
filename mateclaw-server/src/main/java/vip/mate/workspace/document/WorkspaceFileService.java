package vip.mate.workspace.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.workspace.document.event.WorkspaceFileChangedEvent;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workspace.document.repository.WorkspaceFileMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作区文件服务
 * <p>
 * 管理 Agent 级别的 Markdown 文档，支持启用/禁用、排序，
 * 并将启用的文件内容拼接为系统提示词。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceFileService {

    private final WorkspaceFileMapper fileMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 列出 Agent 的所有工作区文件（按排序 + 文件名排列）
     */
    public List<WorkspaceFileEntity> listFiles(Long agentId) {
        List<WorkspaceFileEntity> files = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId)
                        .orderByAsc(WorkspaceFileEntity::getSortOrder)
                        .orderByAsc(WorkspaceFileEntity::getFilename));
        // 返回列表时不包含 content（减少传输）
        files.forEach(f -> f.setContent(null));
        return files;
    }

    /**
     * 读取单个文件（含内容）
     */
    public WorkspaceFileEntity getFile(Long agentId, String filename) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId)
                        .eq(WorkspaceFileEntity::getFilename, filename));
    }

    /**
     * 创建或更新文件
     */
    @Transactional
    public WorkspaceFileEntity saveFile(Long agentId, String filename, String content) {
        WorkspaceFileEntity existing = getFile(agentId, filename);
        long size = content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0;

        if (existing != null) {
            existing.setContent(content);
            existing.setFileSize(size);
            fileMapper.updateById(existing);
            eventPublisher.publishEvent(new WorkspaceFileChangedEvent(agentId, filename));
            return existing;
        } else {
            WorkspaceFileEntity entity = new WorkspaceFileEntity();
            entity.setAgentId(agentId);
            entity.setFilename(filename);
            entity.setContent(content);
            entity.setFileSize(size);
            entity.setEnabled(false);
            entity.setSortOrder(0);
            fileMapper.insert(entity);
            eventPublisher.publishEvent(new WorkspaceFileChangedEvent(agentId, filename));
            return entity;
        }
    }

    /**
     * 删除文件
     */
    @Transactional
    public void deleteFile(Long agentId, String filename) {
        fileMapper.delete(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId)
                        .eq(WorkspaceFileEntity::getFilename, filename));
        eventPublisher.publishEvent(new WorkspaceFileChangedEvent(agentId, filename));
    }

    /**
     * 获取当前启用的系统提示文件名列表（有序）
     */
    public List<String> getPromptFiles(Long agentId) {
        return fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId)
                        .eq(WorkspaceFileEntity::getEnabled, true)
                        .orderByAsc(WorkspaceFileEntity::getSortOrder))
                .stream()
                .map(WorkspaceFileEntity::getFilename)
                .collect(Collectors.toList());
    }

    /**
     * 设置启用的系统提示文件列表（有序）
     * <p>
     * 传入文件名列表，按顺序设置 enabled=true 和 sortOrder；
     * 不在列表中的文件设置 enabled=false。
     */
    @Transactional
    public void setPromptFiles(Long agentId, List<String> filenames) {
        List<WorkspaceFileEntity> allFiles = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId));

        for (WorkspaceFileEntity file : allFiles) {
            int index = filenames.indexOf(file.getFilename());
            if (index >= 0) {
                file.setEnabled(true);
                file.setSortOrder(index);
            } else {
                file.setEnabled(false);
                file.setSortOrder(0);
            }
            fileMapper.updateById(file);
        }
    }

    // ==================== Snippet search ====================

    /** Hard cap on terms passed to the DB filter — keeps the {@code LIKE} chain
     *  short and bounds the per-line scoring cost.
     *  <p>
     *  Sized so a typical CJK query of up to 12 chars (= 6 non-overlapping
     *  2-char windows) lands all of its tokens, not just the first four.
     *  The trade-off: every extra term tightens the AND-LIKE filter, so a
     *  user typing a long phrase whose words are spread across files will
     *  match fewer candidates. Acceptable here because the typical search
     *  is a short phrase ("用户跑步"), and the per-line scorer's term-hit
     *  count keeps relevance ranking stable when only a subset matches.
     *  Sliding-bigram tokenization is the next lever if real query data
     *  shows a recall problem on long phrases. */
    private static final int MAX_TERMS = 6;

    /** Minimum query length below which search is a no-op. Two characters
     *  comfortably covers a single CJK word; one character would return the
     *  bulk of every file. */
    private static final int MIN_QUERY_LENGTH = 2;

    /** Candidate file cap. Picked large enough that scope filtering plus
     *  AND-LIKE almost always leaves room for the relevant memory set, but
     *  small enough that line-level scoring stays cheap. */
    private static final int CANDIDATE_FILE_CAP = 50;

    /** Per-file hit cap. Prevents a single very long file (multi-week daily
     *  ledger, copy-pasted log) from monopolising the result set. */
    private static final int PER_FILE_HIT_CAP = 5;

    /** Snippet context window: characters before and after the first matched
     *  term on the line. The original line is clipped to this window before
     *  highlighting. */
    private static final int SNIPPET_CONTEXT = 80;

    /**
     * Keyword-search the agent's workspace files and return line-level snippet
     * hits ranked by term-match count weighted by per-file importance.
     * <p>
     * Pipeline:
     * <ol>
     *   <li>Tokenize {@code query} on whitespace and CJK/Latin boundaries.
     *       CJK runs are split into non-overlapping 2-char windows; Latin /
     *       digit runs are kept as whole tokens. Dedupe and cap at {@value
     *       #MAX_TERMS}.</li>
     *   <li>Pull candidate rows via {@link com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper}:
     *       {@code agent_id} match, optional {@code filename LIKE prefix%} OR
     *       group, and one {@code content LIKE %term%} per token (AND).</li>
     *   <li>For each candidate file, scan lines and collect up to {@value
     *       #PER_FILE_HIT_CAP} hits scored by {@code termsHit * fileWeight}.</li>
     *   <li>Build a head/match/tail snippet around the first matched term on
     *       the line, wrap each term in {@code [[...]]}, sort by score
     *       descending and return the top {@code limit} results.</li>
     * </ol>
     *
     * @param agentId          The agent whose workspace files are searched.
     *                         Required; null returns empty.
     * @param query            The user-supplied search string. Returns empty
     *                         if {@code null}, blank, or shorter than {@value
     *                         #MIN_QUERY_LENGTH} characters after trim.
     * @param filenamePrefixes Optional filename prefix whitelist (each value
     *                         is a {@code likeRight} prefix). {@code null} or
     *                         empty means "all files for this agent".
     * @param limit            Maximum number of hits returned across all
     *                         files. Capped at the candidate-file scan
     *                         output naturally; the caller is expected to
     *                         choose a sensible value (1-30 typical).
     */
    public List<MemorySearchHit> searchSnippets(Long agentId, String query,
                                                Set<String> filenamePrefixes, int limit) {
        if (agentId == null || limit <= 0) {
            return List.of();
        }
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<WorkspaceFileEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkspaceFileEntity::getAgentId, agentId);

        if (filenamePrefixes != null && !filenamePrefixes.isEmpty()) {
            // Group prefix conditions inside a single AND-bracketed OR chain
            // so they don't interleave with the content-LIKE terms.
            wrapper.and(w -> {
                boolean[] first = {true};
                for (String prefix : filenamePrefixes) {
                    if (prefix == null || prefix.isEmpty()) continue;
                    if (first[0]) {
                        w.likeRight(WorkspaceFileEntity::getFilename, prefix);
                        first[0] = false;
                    } else {
                        w.or().likeRight(WorkspaceFileEntity::getFilename, prefix);
                    }
                }
            });
        }
        for (String term : terms) {
            wrapper.like(WorkspaceFileEntity::getContent, term);
        }
        // Dialect-transparent LIMIT; @TableLogic appends deleted = 0 for us.
        wrapper.last("LIMIT " + CANDIDATE_FILE_CAP);

        List<WorkspaceFileEntity> candidates = fileMapper.selectList(wrapper);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<MemorySearchHit> all = new ArrayList<>();
        for (WorkspaceFileEntity file : candidates) {
            all.addAll(extractHitsFromFile(file, terms));
        }
        all.sort(Comparator.comparingDouble(MemorySearchHit::score).reversed()
                .thenComparing(MemorySearchHit::filename)
                .thenComparingInt(MemorySearchHit::lineNumber));
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /** Split a query into searchable terms. Whitespace and non-letter/digit
     *  separators end a token; CJK ↔ Latin/digit transitions end a token;
     *  CJK runs of length ≥ 2 are further chunked into non-overlapping
     *  2-char windows (e.g. "用户喜欢" → ["用户", "喜欢"]). The result is
     *  deduped (preserving order) and capped at {@value #MAX_TERMS}. */
    static List<String> tokenize(String query) {
        if (query == null) return List.of();
        List<String> raw = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int prevType = -1; // 0 = CJK, 1 = Latin/digit, -1 = separator / not started
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            int type = classify(c);
            if (type == -1) {
                flush(cur, raw);
                prevType = -1;
                continue;
            }
            if (prevType != -1 && type != prevType) {
                flush(cur, raw);
            }
            cur.append(c);
            prevType = type;
        }
        flush(cur, raw);

        // Split long CJK runs into 2-char windows so a query like
        // "用户喜欢周日早上跑步" yields multiple usable terms instead of a
        // single substring rarely present verbatim in stored memory.
        List<String> exploded = new ArrayList<>();
        for (String token : raw) {
            if (!token.isEmpty() && classify(token.charAt(0)) == 0 && token.length() > 2) {
                for (int i = 0; i + 2 <= token.length(); i += 2) {
                    exploded.add(token.substring(i, i + 2));
                }
                if (token.length() % 2 == 1) {
                    // Trailing single char: pair it with the previous char as
                    // an overlap so it can still match (better than dropping).
                    exploded.add(token.substring(token.length() - 2));
                }
            } else {
                exploded.add(token);
            }
        }

        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        for (String t : exploded) {
            if (!t.isEmpty()) dedupe.add(t);
        }
        if (dedupe.size() <= MAX_TERMS) {
            return List.copyOf(dedupe);
        }
        List<String> capped = new ArrayList<>(MAX_TERMS);
        int i = 0;
        for (String t : dedupe) {
            if (i++ >= MAX_TERMS) break;
            capped.add(t);
        }
        return Collections.unmodifiableList(capped);
    }

    private static int classify(char c) {
        if (c >= 0x4E00 && c <= 0x9FFF) return 0;       // CJK Unified Ideographs
        if (c >= 0x3400 && c <= 0x4DBF) return 0;       // CJK Extension A
        if (Character.isLetterOrDigit(c)) return 1;
        return -1;
    }

    private static void flush(StringBuilder cur, List<String> sink) {
        if (cur.length() > 0) {
            sink.add(cur.toString());
            cur.setLength(0);
        }
    }

    private List<MemorySearchHit> extractHitsFromFile(WorkspaceFileEntity file, List<String> terms) {
        String content = file.getContent();
        if (content == null || content.isEmpty()) return List.of();
        String[] lines = content.split("\n", -1);
        double weight = fileWeight(file.getFilename());
        List<MemorySearchHit> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int termsHit = 0;
            for (String term : terms) {
                if (line.contains(term)) termsHit++;
            }
            if (termsHit == 0) continue;
            double score = termsHit * weight;
            hits.add(new MemorySearchHit(file.getFilename(), i + 1,
                    buildSnippet(line, terms), score));
            if (hits.size() >= PER_FILE_HIT_CAP) break;
        }
        return hits;
    }

    /** Per-file importance multiplier. {@code MEMORY.md} is the consolidated
     *  long-term memory; daily ledger files under {@code memory/} are
     *  high-recall but noisier; {@code PROFILE.md} is mostly static persona
     *  facts; {@code AGENTS.md} is mostly tool / behavior config and rarely
     *  the right answer to a memory query. */
    private double fileWeight(String filename) {
        if (filename == null) return 0.2;
        if ("MEMORY.md".equals(filename)) return 1.0;
        if (filename.startsWith("memory/")) return 0.7;
        if ("PROFILE.md".equals(filename)) return 0.5;
        if ("AGENTS.md".equals(filename)) return 0.3;
        return 0.2;
    }

    /** Build a {@code head + match + tail} snippet around the first matched
     *  term and wrap every non-overlapping match inside the window with
     *  {@code [[...]]}. Term boundaries are preserved — adjacent matches from
     *  two different terms render as {@code [[t1]][[t2]]}, not as one merged
     *  bracket. Longer terms claim their span first so a shorter overlapping
     *  term cannot fragment a longer match. */
    private String buildSnippet(String line, List<String> terms) {
        int firstMatch = Integer.MAX_VALUE;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            int idx = line.indexOf(term);
            if (idx >= 0 && idx < firstMatch) firstMatch = idx;
        }
        if (firstMatch == Integer.MAX_VALUE) {
            return line;
        }

        int start = Math.max(0, firstMatch - SNIPPET_CONTEXT);
        int end = Math.min(line.length(), firstMatch + SNIPPET_CONTEXT);
        String clipped = line.substring(start, end);
        boolean hasPrefix = start > 0;
        boolean hasSuffix = end < line.length();

        List<String> longestFirst = new ArrayList<>(terms);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());

        boolean[] covered = new boolean[clipped.length()];
        List<int[]> spans = new ArrayList<>();
        for (String term : longestFirst) {
            if (term.isEmpty()) continue;
            int from = 0;
            int idx;
            while ((idx = clipped.indexOf(term, from)) >= 0) {
                int spanEnd = idx + term.length();
                boolean overlap = false;
                for (int k = idx; k < spanEnd; k++) {
                    if (covered[k]) { overlap = true; break; }
                }
                if (!overlap) {
                    for (int k = idx; k < spanEnd; k++) covered[k] = true;
                    spans.add(new int[]{idx, spanEnd});
                }
                from = spanEnd;
            }
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));

        StringBuilder sb = new StringBuilder(clipped.length() + spans.size() * 4 + 6);
        if (hasPrefix) sb.append("...");
        int pos = 0;
        for (int[] s : spans) {
            sb.append(clipped, pos, s[0]);
            sb.append("[[").append(clipped, s[0], s[1]).append("]]");
            pos = s[1];
        }
        sb.append(clipped, pos, clipped.length());
        if (hasSuffix) sb.append("...");
        return sb.toString();
    }

    /**
     * 将启用的工作区文件拼接为系统提示词
     * <p>
     * 每个文件以 "--- {filename} ---\n{content}\n" 的格式拼接。
     * 如果没有启用的文件，返回 null。
     */
    public String buildSystemPrompt(Long agentId) {
        List<WorkspaceFileEntity> enabledFiles = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFileEntity>()
                        .eq(WorkspaceFileEntity::getAgentId, agentId)
                        .eq(WorkspaceFileEntity::getEnabled, true)
                        .orderByAsc(WorkspaceFileEntity::getSortOrder));

        if (enabledFiles.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (WorkspaceFileEntity file : enabledFiles) {
            if (file.getContent() != null && !file.getContent().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("--- ").append(file.getFilename()).append(" ---\n");
                sb.append(file.getContent().trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
