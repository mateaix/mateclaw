package vip.mate.agent.graph.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks source references that were actually observed through tool results.
 */
public record SourceEvidenceLedger(
        Set<String> sourcePaths,
        Set<String> sourceSymbols,
        Set<String> failedPaths,
        Set<String> wikiPageTitles,
        Set<String> wikiChunkIds,
        Set<SourceEvidenceLedger.WikiCitation> wikiCitations
) implements Serializable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JAVA_PATH = Pattern.compile(
            "(?:[A-Za-z]:)?[A-Za-z0-9_./\\\\-]+\\.java\\b");
    private static final Pattern JAVA_FILE_REF = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\.java\\b");
    private static final Pattern JAVA_SYMBOL_REF = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9_]*(?:Controller|Service|ServiceImpl|Node|Tool|Parser|Resolver|Manager|Syncer|Mapper|Entity|Repository|Dispatcher|Executor|Accessor|Builder|Policy|Guard)\\b");
    private static final Pattern DECLARED_TYPE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)\\b");
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)\\]");

    public SourceEvidenceLedger {
        sourcePaths = Set.copyOf(sourcePaths == null ? Set.of() : sourcePaths);
        sourceSymbols = Set.copyOf(sourceSymbols == null ? Set.of() : sourceSymbols);
        failedPaths = Set.copyOf(failedPaths == null ? Set.of() : failedPaths);
        wikiPageTitles = Set.copyOf(wikiPageTitles == null ? Set.of() : wikiPageTitles);
        wikiChunkIds = Set.copyOf(wikiChunkIds == null ? Set.of() : wikiChunkIds);
        wikiCitations = Set.copyOf(wikiCitations == null ? Set.of() : wikiCitations);
    }

    public static SourceEvidenceLedger empty() {
        return new SourceEvidenceLedger(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static SourceEvidenceLedger fromToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return empty();
        }
        Builder builder = new Builder();
        for (ToolResponseMessage.ToolResponse response : responses) {
            String data = response.responseData();
            if (data == null || data.isBlank()) {
                continue;
            }
            if (isReadFileTool(response.name())) {
                recordReadFile(data, builder);
            } else {
                recordPlainTextEvidence(data, builder);
                recordWikiEvidence(data, builder);
            }
        }
        return builder.build();
    }

    public SourceEvidenceLedger merge(SourceEvidenceLedger other) {
        if (other == null || !other.hasEvidence()) {
            return this;
        }
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        wikiPageTitles.forEach(builder::wikiPageTitle);
        wikiChunkIds.forEach(builder::wikiChunkId);
        wikiCitations.forEach(builder::wikiCitation);
        other.sourcePaths.forEach(builder::sourcePath);
        other.sourceSymbols.forEach(builder::symbol);
        other.failedPaths.forEach(builder::failedPath);
        other.wikiPageTitles.forEach(builder::wikiPageTitle);
        other.wikiChunkIds.forEach(builder::wikiChunkId);
        other.wikiCitations.forEach(builder::wikiCitation);
        return builder.build();
    }

    public SourceEvidenceLedger withSourcePath(String path) {
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        wikiPageTitles.forEach(builder::wikiPageTitle);
        wikiChunkIds.forEach(builder::wikiChunkId);
        wikiCitations.forEach(builder::wikiCitation);
        builder.sourcePath(path);
        return builder.build();
    }

    public SourceEvidenceLedger withWikiPageTitle(String title) {
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        wikiPageTitles.forEach(builder::wikiPageTitle);
        wikiChunkIds.forEach(builder::wikiChunkId);
        wikiCitations.forEach(builder::wikiCitation);
        builder.wikiPageTitle(title);
        return builder.build();
    }

    public SourceEvidenceLedger withWikiChunkId(String chunkId) {
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        wikiPageTitles.forEach(builder::wikiPageTitle);
        wikiChunkIds.forEach(builder::wikiChunkId);
        wikiCitations.forEach(builder::wikiCitation);
        builder.wikiChunkId(chunkId);
        return builder.build();
    }

    public boolean hasEvidence() {
        return !sourcePaths.isEmpty() || !sourceSymbols.isEmpty() || !failedPaths.isEmpty()
                || hasWikiEvidence();
    }

    public boolean hasWikiEvidence() {
        return !wikiPageTitles.isEmpty() || !wikiChunkIds.isEmpty() || !wikiCitations.isEmpty();
    }

    public boolean hasWikiPageTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String normalized = title.trim();
        return wikiPageTitles.contains(normalized)
                || wikiPageTitles.stream().anyMatch(t -> t.equalsIgnoreCase(normalized));
    }

    public boolean hasWikiChunkId(String chunkId) {
        return chunkId != null && wikiChunkIds.contains(chunkId);
    }

    public boolean hasWikiCitationIndex(int index) {
        return wikiCitations.stream().anyMatch(c -> c.index() == index);
    }

    public boolean hasPath(String path) {
        String normalized = normalizePath(path);
        return sourcePaths.contains(normalized) || sourcePaths.stream().anyMatch(p -> p.endsWith("/" + normalized));
    }

    public boolean hasSymbol(String symbol) {
        return sourceSymbols.contains(symbol);
    }

    public Validation validateAnswer(String answer) {
        if (answer == null || answer.isBlank() || !hasEvidence()) {
            return Validation.ok();
        }
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        LinkedHashSet<String> unsupportedFileStems = new LinkedHashSet<>();

        Matcher fileMatcher = JAVA_FILE_REF.matcher(answer);
        while (fileMatcher.find()) {
            String ref = fileMatcher.group();
            if (!hasFileName(ref)) {
                unsupported.add(ref);
                unsupportedFileStems.add(ref.substring(0, ref.length() - ".java".length()));
            }
        }

        Matcher symbolMatcher = JAVA_SYMBOL_REF.matcher(answer);
        while (symbolMatcher.find()) {
            String ref = symbolMatcher.group();
            if (!unsupportedFileStems.contains(ref) && !sourceSymbols.contains(ref) && !hasFileName(ref + ".java")) {
                unsupported.add(ref);
            }
        }

        validateWikiCitations(answer, unsupported);

        return unsupported.isEmpty() ? Validation.ok() : new Validation(false, List.copyOf(unsupported));
    }

    public String appendWikiSourceTable(String answer) {
        if (answer == null || answer.isBlank() || wikiCitations.isEmpty()) {
            return answer;
        }
        LinkedHashSet<Integer> used = citationIndexesIn(answer);
        if (used.isEmpty()) {
            return answer;
        }

        String result = answer;
        StringBuilder additions = new StringBuilder();

        for (Integer index : used) {
            WikiCitation citation = wikiCitation(index);
            if (citation == null) {
                continue;
            }
            String canonicalLine = citation.sourceLine();
            if (sourceLineFor(result, index) != null) {
                // Normalize in-place: replace the existing (possibly
                // non-canonical) source line with the standard format so
                // the frontend can reliably parse the source table.
                result = replaceSourceLine(result, index, canonicalLine);
            } else {
                if (additions.isEmpty()) {
                    additions.append("\n\n来源：");
                }
                additions.append("\n").append(canonicalLine);
            }
        }

        // If source lines were normalized in-place but no 来源： header
        // exists, insert one before the first source line so the frontend
        // preprocessWikiCitations() can locate the source table.
        if (additions.isEmpty() && !result.contains("来源：")) {
            java.util.regex.Matcher firstSource = java.util.regex.Pattern
                    .compile("(?m)^\\[")
                    .matcher(result);
            if (firstSource.find()) {
                int pos = firstSource.start();
                result = result.substring(0, pos) + "\n\n来源：\n" + result.substring(pos);
            }
        }

        return additions.isEmpty() ? result : result + additions;
    }

    private void validateWikiCitations(String answer, LinkedHashSet<String> unsupported) {
        if (wikiCitations.isEmpty()) {
            return;
        }

        LinkedHashSet<Integer> indexes = citationIndexesIn(answer);
        if (indexes.isEmpty()) {
            unsupported.add("missing wiki citation [n]");
            return;
        }

        for (Integer index : indexes) {
            WikiCitation citation = wikiCitation(index);
            if (citation == null) {
                unsupported.add("wiki citation [" + index + "]");
                continue;
            }
            String sourceLine = sourceLineFor(answer, index);
            if (sourceLine == null) {
                unsupported.add("wiki source table [" + index + "]");
            } else if (!citation.matchesSourceLine(sourceLine)) {
                unsupported.add("wiki source title for [" + index + "]");
            }
        }
    }

    private LinkedHashSet<Integer> citationIndexesIn(String answer) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Matcher citationMatcher = CITATION_MARKER.matcher(answer);
        while (citationMatcher.find()) {
            try {
                indexes.add(Integer.parseInt(citationMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return indexes;
    }

    private WikiCitation wikiCitation(int index) {
        return wikiCitations.stream()
                .filter(c -> c.index() == index)
                .findFirst()
                .orElse(null);
    }

    private static String sourceLineFor(String answer, int index) {
        Pattern pattern = Pattern.compile("(?m)^\\s*\\[" + index + "\\]\\s+(.+)$");
        Matcher matcher = pattern.matcher(answer);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * Replace the existing source line for {@code index} with the canonical
     * form. Matches a full line starting with optional whitespace, {@code [N]},
     * then any content, and replaces it in-place so the frontend can reliably
     * parse the source table to build a citation index → title map.
     */
    private static String replaceSourceLine(String answer, int index, String canonicalLine) {
        Pattern pattern = Pattern.compile("(?m)^\\s*\\[" + index + "\\]\\s+.+$");
        return pattern.matcher(answer).replaceFirst(
                java.util.regex.Matcher.quoteReplacement(canonicalLine));
    }

    private boolean hasFileName(String fileName) {
        String normalized = normalizePath(fileName);
        return sourcePaths.stream().anyMatch(p -> p.equals(normalized) || p.endsWith("/" + normalized));
    }

    private static boolean isReadFileTool(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "_");
        return normalized.equals("read_file");
    }

    private static void recordReadFile(String data, Builder builder) {
        try {
            JsonNode root = MAPPER.readTree(data);
            String filePath = root.path("filePath").asText("");
            if (root.path("error").asBoolean(false)) {
                builder.failedPath(filePath);
                return;
            }
            builder.sourcePath(filePath);
            String content = root.path("content").asText("");
            recordSymbols(content, builder);
        } catch (Exception ignored) {
            recordPlainTextEvidence(data, builder);
        }
    }

    private static void recordPlainTextEvidence(String text, Builder builder) {
        Matcher matcher = JAVA_PATH.matcher(text);
        while (matcher.find()) {
            builder.sourcePath(matcher.group());
        }
        recordSymbols(text, builder);
    }

    private static void recordWikiEvidence(String text, Builder builder) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            recordWikiArray(root.path("chunks"), builder);
            recordWikiArray(root.path("pages"), builder);
            String title = root.path("title").asText("");
            String rawTitle = root.path("rawTitle").asText("");
            if (!title.isBlank()) {
                builder.wikiPageTitle(title);
                builder.wikiCitation(new WikiCitation(1, "", title, "", null));
            }
            if (!rawTitle.isBlank()) {
                builder.wikiPageTitle(rawTitle);
                builder.wikiCitation(new WikiCitation(1, "", rawTitle, "", null));
            }
        } catch (Exception ignored) {
        }
    }

    private static void recordWikiArray(JsonNode nodes, Builder builder) {
        if (!nodes.isArray()) {
            return;
        }
        int ordinal = 1;
        for (JsonNode node : nodes) {
            int index = node.path("index").isInt() ? node.path("index").asInt() : ordinal;
            String title = firstNonBlank(node.path("rawTitle").asText(""), node.path("title").asText(""));
            String chunkId = node.path("chunkId").asText("");
            String section = node.path("section").asText("");
            Integer pageNumber = node.hasNonNull("pageNumber") ? node.path("pageNumber").asInt() : null;
            if (!title.isBlank()) {
                builder.wikiPageTitle(title);
            }
            if (!chunkId.isBlank()) {
                builder.wikiChunkId(chunkId);
            }
            if (!title.isBlank() || !chunkId.isBlank()) {
                builder.wikiCitation(new WikiCitation(index, chunkId, title, section, pageNumber));
            }
            ordinal++;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static void recordSymbols(String text, Builder builder) {
        Matcher matcher = DECLARED_TYPE.matcher(text);
        while (matcher.find()) {
            builder.symbol(matcher.group(1));
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private static final class Builder {
        private final LinkedHashSet<String> sourcePaths = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceSymbols = new LinkedHashSet<>();
        private final LinkedHashSet<String> failedPaths = new LinkedHashSet<>();
        private final LinkedHashSet<String> wikiPageTitles = new LinkedHashSet<>();
        private final LinkedHashSet<String> wikiChunkIds = new LinkedHashSet<>();
        private final LinkedHashSet<WikiCitation> wikiCitations = new LinkedHashSet<>();

        void sourcePath(String path) {
            String normalized = normalizePath(path);
            if (normalized.isBlank()) {
                return;
            }
            sourcePaths.add(normalized);
            String fileName = Path.of(normalized).getFileName() != null
                    ? Path.of(normalized).getFileName().toString() : normalized;
            if (fileName.endsWith(".java")) {
                sourceSymbols.add(fileName.substring(0, fileName.length() - ".java".length()));
            }
        }

        void symbol(String symbol) {
            if (symbol != null && !symbol.isBlank()) {
                sourceSymbols.add(symbol.trim());
            }
        }

        void failedPath(String path) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) {
                failedPaths.add(normalized);
            }
        }

        void wikiPageTitle(String title) {
            if (title != null && !title.isBlank()) {
                wikiPageTitles.add(title.trim());
            }
        }

        void wikiChunkId(String chunkId) {
            if (chunkId != null && !chunkId.isBlank()) {
                wikiChunkIds.add(chunkId.trim());
            }
        }

        void wikiCitation(WikiCitation citation) {
            if (citation == null || citation.index() < 1) {
                return;
            }
            wikiCitations.removeIf(existing -> existing.index() == citation.index());
            wikiCitations.add(citation.normalized());
        }

        SourceEvidenceLedger build() {
            return new SourceEvidenceLedger(sourcePaths, sourceSymbols, failedPaths,
                    wikiPageTitles, wikiChunkIds, wikiCitations);
        }
    }

    public record WikiCitation(int index, String chunkId, String title,
                               String section, Integer pageNumber) implements Serializable {
        WikiCitation normalized() {
            return new WikiCitation(index,
                    chunkId == null ? "" : chunkId.trim(),
                    title == null ? "" : title.trim(),
                    section == null ? "" : section.trim(),
                    pageNumber);
        }

        String sourceLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(index).append("] ");
            sb.append(title == null || title.isBlank() ? "chunkId=" + chunkId : title);
            if (section != null && !section.isBlank()) {
                sb.append(" - ").append(section);
            }
            if (pageNumber != null) {
                sb.append(" - page ").append(pageNumber);
            }
            return sb.toString();
        }

        boolean matchesSourceLine(String line) {
            if (line == null || line.isBlank()) {
                return false;
            }
            if (title != null && !title.isBlank() && line.contains(title)) {
                return true;
            }
            return chunkId != null && !chunkId.isBlank() && line.contains(chunkId);
        }
    }

    public record Validation(boolean valid, List<String> unsupportedReferences) {
        public static Validation ok() {
            return new Validation(true, List.of());
        }
    }
}
