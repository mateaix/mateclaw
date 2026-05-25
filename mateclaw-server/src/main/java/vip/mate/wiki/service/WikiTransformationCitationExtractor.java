package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiChunkEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverse-citation parser: scans the markdown output of a transformation run
 * for references that point back into the source raw material (e.g.
 * {@code 第 14 页}, {@code page 14}, {@code 示例题号 1, 5}, {@code 第 5 题})
 * and resolves each reference to a chunk in the source raw.
 *
 * <p>The resolved chunk IDs are passed to
 * {@link WikiCitationService#buildCitations(Long, Long, List)} so the
 * synthesis page cites only the specific chunks the LLM said it relied on
 * rather than every chunk of the source raw — this keeps the citation
 * graph (and the relation signals derived from shared citations) clean.
 *
 * <p>When no parseable references are found the extractor returns {@code 0}
 * without touching existing citations; the caller can decide whether to
 * fall back to the raw-level default citation build.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiTransformationCitationExtractor {

    private final WikiChunkService chunkService;
    private final WikiCitationService citationService;

    /** {@code 第 N 页} / {@code 页 N} / {@code page N} / {@code p.N} / {@code p N} */
    private static final Pattern PAGE_REF = Pattern.compile(
            "(?:第\\s*(\\d+)\\s*页|页\\s+(\\d+)|[Pp]age\\s+(\\d+)|p\\.\\s*(\\d+)|p\\s+(\\d+))");

    /** {@code 第 N 题} / {@code 例(题)? N} / {@code 题 N} / {@code Problem N} / {@code Example N} */
    private static final Pattern PROBLEM_REF = Pattern.compile(
            "(?:第\\s*(\\d+)\\s*题|例(?:题)?\\s*(\\d+)|题\\s+(\\d+)|[Pp]roblem\\s+(\\d+)|[Ee]xample\\s+(\\d+))");

    /**
     * Run extract → resolve → write. Returns the count of chunk citations
     * actually written. Best-effort: any internal exception is logged and
     * the call returns 0 so callers can fall back without surfacing the
     * error to the user.
     */
    public int extractAndApply(Long pageId, Long kbId, Long sourceRawId, String output) {
        if (pageId == null || kbId == null || sourceRawId == null) return 0;
        if (output == null || output.isBlank()) return 0;
        try {
            Set<Integer> pageRefs = parseNumeric(output, PAGE_REF);
            Set<Integer> problemRefs = parseNumeric(output, PROBLEM_REF);
            if (pageRefs.isEmpty() && problemRefs.isEmpty()) return 0;

            List<WikiChunkEntity> chunks = chunkService.listByRawId(sourceRawId);
            if (chunks.isEmpty()) return 0;

            Set<Long> hitIds = new LinkedHashSet<>();
            for (WikiChunkEntity chunk : chunks) {
                if (chunkMatches(chunk, pageRefs, problemRefs)) {
                    hitIds.add(chunk.getId());
                }
            }
            if (hitIds.isEmpty()) return 0;

            citationService.buildCitations(pageId, kbId, new ArrayList<>(hitIds));
            log.info("[WikiCitationExtractor] page={} kb={} cited {} chunks "
                            + "(pageRefs={}, problemRefs={})",
                    pageId, kbId, hitIds.size(), pageRefs, problemRefs);
            return hitIds.size();
        } catch (Exception e) {
            log.warn("[WikiCitationExtractor] extract failed pageId={}: {}", pageId, e.getMessage());
            return 0;
        }
    }

    /** Collect every integer captured by any group of the supplied pattern. */
    private static Set<Integer> parseNumeric(String text, Pattern pattern) {
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) {
                    try { out.add(Integer.parseInt(g)); break; }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return out;
    }

    /**
     * A chunk is a citation hit when:
     * <ul>
     *   <li>its {@code pageNumber} matches one of the {@code pageRefs}, OR</li>
     *   <li>its {@code content} contains a problem marker matching one of
     *       {@code problemRefs} (e.g. "第 5 题" / "5." / "Problem 5").</li>
     * </ul>
     */
    private boolean chunkMatches(WikiChunkEntity chunk, Set<Integer> pageRefs, Set<Integer> problemRefs) {
        if (!pageRefs.isEmpty()
                && chunk.getPageNumber() != null
                && pageRefs.contains(chunk.getPageNumber())) {
            return true;
        }
        if (!problemRefs.isEmpty() && chunk.getContent() != null) {
            String content = chunk.getContent();
            for (Integer n : problemRefs) {
                if (containsProblemMarker(content, n)) return true;
            }
        }
        return false;
    }

    /** Match any of the conventional problem-number forms in the chunk content. */
    private static boolean containsProblemMarker(String content, int n) {
        if (content == null) return false;
        return content.contains("第 " + n + " 题")
                || content.contains("第" + n + "题")
                || content.contains("例 " + n)
                || content.contains("例" + n)
                || content.contains("题 " + n)
                || content.contains("Problem " + n)
                || content.contains("Example " + n)
                // Common "1.", "2." problem-number markers at line start. Cheap
                // contains-check rather than a regex anchor — the false-positive
                // rate is low because we only match when problemRefs is non-empty,
                // i.e. the LLM explicitly cited a numbered example.
                || content.contains("\n" + n + ". ")
                || content.startsWith(n + ". ");
    }
}
