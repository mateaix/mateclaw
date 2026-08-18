package vip.mate.tool.builtin;

import lombok.extern.slf4j.Slf4j;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a user-supplied file path against the current conversation's chat-upload
 * directory ({@code {upload-root}/{conversationId}/}).
 * <p>
 * The upload root is workspace/agent-aware. When the active agent has a resolved
 * {@code workspaceBasePath} (carried on {@link ToolExecutionContext}), attachments
 * live under {@code {workspaceBasePath}/chat-uploads/{conversationId}/}; otherwise
 * they live under the configurable default root (legacy {@code data/chat-uploads}).
 * Reads check both locations so attachments written before the workspace-aware
 * relocation (under the default dir) still resolve.
 * <p>
 * Chat attachments are stored as {@code {timestamp}_{safeFilename}} where
 * {@code safeFilename} replaces every non-{@code [a-zA-Z0-9._-]} character with
 * {@code _}. This means a file uploaded as {@code 人人有虾.docx} is stored on disk
 * as e.g. {@code 1777391026594_____.docx}. The LLM only ever sees the original
 * filename in the rendered "[附件] foo.docx" prefix, so when a tool gets called
 * with the original name it won't match anything on disk via direct lookup.
 * <p>
 * This helper rescues such calls by matching basenames inside the conversation's
 * upload directory. Used by both {@link ReadFileTool} and {@link DocumentExtractTool}.
 *
 * @see ChatUploadLocationResolver the Spring-managed resolver that drives the
 *      same workspace/agent precedence from the off-request path (downloaders,
 *      cleanup, file-serving).
 */
@Slf4j
public final class ChatUploadResolver {

    /**
     * Sub-directory appended under a workspace/agent base path. Kept in sync
     * with {@link ChatUploadLocationResolver#UPLOAD_SUBDIR}.
     */
    private static final String UPLOAD_SUBDIR = ChatUploadLocationResolver.UPLOAD_SUBDIR;

    /**
     * Configurable default upload root, registered once at startup from
     * {@code mateclaw.chat.upload.base-dir}. Defaults to the legacy
     * {@code data/chat-uploads} until {@link #setDefaultRoot} is called.
     */
    private static volatile Path defaultRoot = Paths.get("data", "chat-uploads");

    private ChatUploadResolver() {}

    /**
     * Register the configurable default upload root. Called once at startup by
     * {@code ChatUploadAutoConfiguration}. A {@code null}/blank path restores
     * the legacy {@code data/chat-uploads}.
     */
    public static void setDefaultRoot(Path path) {
        defaultRoot = (path == null)
                ? Paths.get("data", "chat-uploads")
                : path.toAbsolutePath().normalize();
    }

    /**
     * @return absolute path of the matched attachment, or {@code null} if no match
     */
    static Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String conversationId = ToolExecutionContext.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }

        for (Path uploadDir : candidateUploadDirs(conversationId)) {
            Path matched = resolveIn(rawPath, uploadDir);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /**
     * Ordered candidate upload directories for a conversation: the
     * workspace-scoped dir first (when a base path is active), then the default
     * fallback dir. De-duplicated so the two coincide (no base path configured)
     * is a single lookup.
     */
    private static List<Path> candidateUploadDirs(String conversationId) {
        Set<Path> dirs = new LinkedHashSet<>();
        String basePath = ToolExecutionContext.workspaceBasePath();
        if (basePath != null && !basePath.isBlank()) {
            Path scopedRoot = Paths.get(basePath).toAbsolutePath().normalize().resolve(UPLOAD_SUBDIR);
            addConversationDirs(dirs, scopedRoot, conversationId);
        }
        addConversationDirs(dirs, defaultRoot, conversationId);
        return new ArrayList<>(dirs);
    }

    /**
     * Add a conversation's attachment dir under {@code root} to {@code dirs}:
     * the sanitized segment first (matching the write path), then — for
     * backward compatibility with pre-fix Linux uploads that used the raw id
     * verbatim — the raw-id dir when it differs and is a legal path on this OS.
     */
    private static void addConversationDirs(Set<Path> dirs, Path root, String conversationId) {
        String safe = ChatUploadLocationResolver.sanitizeSegment(conversationId);
        dirs.add(root.resolve(safe).toAbsolutePath().normalize());
        if (!safe.equals(conversationId)) {
            try {
                dirs.add(root.resolve(conversationId).toAbsolutePath().normalize());
            } catch (InvalidPathException ignore) {
                // Raw id illegal on this filesystem (e.g. ':' on Windows) — no
                // legacy attachments could exist there.
            }
        }
    }

    private static Path resolveIn(String rawPath, Path uploadDir) {
        if (!Files.isDirectory(uploadDir)) {
            return null;
        }
        String basename = basenameOf(rawPath);
        if (basename == null || basename.isBlank()) {
            return null;
        }

        // Attachments may live flat in the conversation dir (legacy layout /
        // date-folders off) or under a yyyy-MM-dd sub-directory; scan both.
        List<Path> scanDirs = ChatUploadLocationResolver.dateScanDirs(uploadDir);

        // An exact stored-name match is unambiguous, so it wins wherever it sits.
        for (Path scanDir : scanDirs) {
            Path direct = scanDir.resolve(basename);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        return newestSuffixMatch(scanDirs, basename);
    }

    /**
     * Last segment of a model-supplied path. The separator is not the running
     * OS's: a model asked about an attachment routinely answers with a
     * hallucinated {@code /app/report.pdf} or {@code C:\Users\me\report.pdf}
     * regardless of the server platform, and on Linux a backslash is a legal
     * file-name character, so {@code Path.getFileName()} alone would hand back
     * the whole Windows-style string. Split on both separators.
     */
    private static String basenameOf(String rawPath) {
        String candidate;
        try {
            Path requested = Paths.get(rawPath).getFileName();
            candidate = requested != null ? requested.toString() : null;
        } catch (Exception e) {
            return null;
        }
        if (candidate == null) {
            return null;
        }
        int backslash = candidate.lastIndexOf('\\');
        return backslash >= 0 ? candidate.substring(backslash + 1) : candidate;
    }

    /**
     * Fallback for when the model passes the original filename instead of the
     * stored name: attachments are stored as {@code {millis}_{safeFilename}}
     * with non-ASCII characters replaced by underscores, so match by sanitized
     * basename suffix. The most recently modified match across every scan dir
     * wins — re-uploading the same filename must resolve to today's copy, not
     * to a same-named one left in the flat dir or an earlier day's dir.
     * <p>
     * Equal timestamps are broken by file name so the pick stays deterministic
     * on filesystems with coarse modification-time resolution (HFS+ stores
     * whole seconds, FAT two): stored names are {@code {millis}_{name}}, so the
     * lexicographically greater name is the later write.
     */
    private static Path newestSuffixMatch(List<Path> scanDirs, String basename) {
        String suffix = "_" + basename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path newest = null;
        FileTime newestTime = null;
        for (Path scanDir : scanDirs) {
            if (!Files.isDirectory(scanDir)) {
                continue;
            }
            try (var stream = Files.list(scanDir)) {
                for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(suffix))::iterator) {
                    FileTime modified = Files.getLastModifiedTime(p);
                    int cmp = (newestTime == null) ? 1 : modified.compareTo(newestTime);
                    if (cmp == 0) {
                        cmp = p.getFileName().toString().compareTo(newest.getFileName().toString());
                    }
                    if (cmp > 0) {
                        newest = p;
                        newestTime = modified;
                    }
                }
            } catch (IOException e) {
                log.warn("[ChatUploadResolver] Failed to scan chat-upload dir {}: {}", scanDir, e.getMessage());
            }
        }
        return newest;
    }
}
