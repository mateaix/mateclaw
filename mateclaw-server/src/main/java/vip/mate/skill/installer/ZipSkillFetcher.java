package vip.mate.skill.installer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses a ZIP-packaged Skill into a {@link SkillBundle}.
 * <p>
 * Used by both the upload endpoint (MultipartFile) and the marketplace install
 * path (downloaded ZIP bytes). Hardened against:
 * <ul>
 *   <li>Zip Slip path traversal</li>
 *   <li>Per-file / total size caps (defaults 1MB / 50MB, configurable via
 *       {@code mateclaw.skill.upload.max-entry-size-mb} /
 *       {@code mateclaw.skill.upload.max-total-size-mb})</li>
 *   <li>Only SKILL.md / references/ / scripts/ entries are kept</li>
 *   <li>Binary entries are skipped with a WARN — bundle storage is text-only,
 *       so decoding them as text would persist corrupted content</li>
 * </ul>
 *
 * <p>Extraction is two-pass: the entire archive is buffered in memory first
 * (cap-protected), then SKILL.md is located and the common parent prefix is
 * stripped from every other entry. This keeps classification correct
 * regardless of the order zip tools write entries — earlier single-pass logic
 * silently dropped {@code scripts/*} entries that streamed before SKILL.md.
 *
 * @author MateClaw Team
 */
@Slf4j
public class ZipSkillFetcher {

    /**
     * Size caps applied while decompressing a bundle. Callers wire these from
     * {@code mateclaw.skill.upload.max-entry-size-mb} /
     * {@code mateclaw.skill.upload.max-total-size-mb}; the defaults preserve
     * the historical 1MB-per-entry / 50MB-total behaviour. The whole archive
     * is buffered in memory during extraction, so raising the total cap
     * raises peak heap usage accordingly.
     */
    public record Limits(long maxEntryBytes, long maxTotalBytes) {
        public static final Limits DEFAULT = ofMb(1, 50);

        public static Limits ofMb(long entryMb, long totalMb) {
            return new Limits(entryMb * 1_000_000L, totalMb * 1_000_000L);
        }

        long totalMb() { return maxTotalBytes / 1_000_000L; }
    }
    private static final String SKILL_MD = "SKILL.md";
    private static final String SKILL_MD_LOWER = "skill.md";

    /**
     * Lowercase file extensions that should be treated as runnable scripts
     * when they appear next to SKILL.md without an explicit {@code scripts/}
     * prefix. Real-world zips from third parties (e.g. the official
     * tencent-meeting-mcp package) put {@code setup.sh} at the package
     * root; without this fallback, those files get logged as "unclassified"
     * and the skill ships with an empty scripts/ directory.
     */
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(
            ".sh", ".bash", ".zsh", ".py", ".js", ".mjs", ".ts",
            ".rb", ".pl", ".php", ".bat", ".cmd", ".ps1");

    /**
     * Lowercase file extensions that are documentation / data alongside
     * SKILL.md and should default to {@code references/} when not nested
     * under an explicit prefix.
     */
    private static final Set<String> REFERENCE_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".csv", ".tsv",
            ".html", ".htm", ".xml", ".toml");

    /**
     * Holds the in-memory result of decompressing a ZIP. Used by callers
     * that want to enrich the SkillBundle with metadata (e.g. marketplace
     * author / icon) that isn't carried inside SKILL.md.
     */
    public record ExtractedSkill(String skillMdContent,
                                 Map<String, String> references,
                                 Map<String, String> scripts) {}

    /** Buffered raw zip entry, awaiting classification once SKILL.md prefix is known. */
    private record RawEntry(String name, String content) {}

    /**
     * Parse an uploaded ZIP file into a SkillBundle. Source type is "zip"
     * and source URL is the original filename.
     */
    public static SkillBundle parse(MultipartFile zipFile, SkillFrontmatterParser parser) throws IOException {
        return parse(zipFile, parser, Limits.DEFAULT);
    }

    /**
     * Parse an uploaded ZIP file into a SkillBundle with explicit size caps
     * (see {@link Limits}).
     */
    public static SkillBundle parse(MultipartFile zipFile, SkillFrontmatterParser parser,
                                    Limits limits) throws IOException {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("ZIP file is empty");
        }
        if (zipFile.getSize() > limits.maxTotalBytes()) {
            throw new IllegalArgumentException("ZIP file too large (max " + limits.totalMb()
                    + "MB; adjust mateclaw.skill.upload.max-total-size-mb)");
        }

        ExtractedSkill extracted;
        try (InputStream is = zipFile.getInputStream()) {
            extracted = extract(is, limits);
        }

        var parsed = parser.parse(extracted.skillMdContent());
        String name = parsed.getName();
        if (name == null || name.isBlank()) {
            String zipName = zipFile.getOriginalFilename();
            if (zipName != null) {
                name = zipName.replaceAll("\\.zip$", "").replaceAll("[^a-zA-Z0-9_-]", "-");
            } else {
                name = "imported-skill";
            }
        }

        log.info("[ZipSkillFetcher] Parsed: name={}, references={}, scripts={}",
                name, extracted.references().size(), extracted.scripts().size());

        Map<String, Object> fm = parsed.getFrontmatter();
        return new SkillBundle(
                name,
                extracted.skillMdContent(),
                extracted.references(),
                extracted.scripts(),
                "zip",
                zipFile.getOriginalFilename(),
                fm != null ? String.valueOf(fm.getOrDefault("version", "1.0.0")) : "1.0.0",
                parsed.getDescription(),
                fm != null ? String.valueOf(fm.getOrDefault("author", "")) : "",
                fm != null ? String.valueOf(fm.getOrDefault("icon", "📦")) : "📦"
        );
    }

    /**
     * Decompress a ZIP stream into in-memory SKILL.md + references + scripts.
     * Throws {@link IllegalArgumentException} if no SKILL.md is present.
     *
     * <p>Two-pass: the first pass buffers every text entry (subject to size
     * caps) and remembers where SKILL.md lives. The second pass strips the
     * SKILL.md parent prefix from each buffered entry and routes it into
     * {@code references} / {@code scripts}. Anything that doesn't match
     * either bucket is logged at WARN level so packaging mistakes surface
     * instead of being silently dropped.
     */
    public static ExtractedSkill extract(InputStream zipStream) throws IOException {
        return extract(zipStream.readAllBytes(), Limits.DEFAULT);
    }

    /** Variant of {@link #extract(InputStream)} with explicit size caps. */
    public static ExtractedSkill extract(InputStream zipStream, Limits limits) throws IOException {
        return extract(zipStream.readAllBytes(), limits);
    }

    /** Fallback charset for archives authored on Chinese Windows (entry names / content in GBK). */
    private static final Charset GBK = Charset.isSupported("GBK") ? Charset.forName("GBK") : null;

    /**
     * Decompress raw ZIP bytes, trying UTF-8 first and falling back to GBK when
     * an entry name fails to decode as UTF-8 — the common failure mode for zips
     * packaged on Chinese Windows, where filenames are GBK and UTF-8 decoding
     * throws a {@link CharacterCodingException}. Buffering the bytes (rather than
     * a one-shot stream) is what makes the retry possible.
     */
    public static ExtractedSkill extract(byte[] zipBytes) throws IOException {
        return extract(zipBytes, Limits.DEFAULT);
    }

    /** Variant of {@link #extract(byte[])} with explicit size caps. */
    public static ExtractedSkill extract(byte[] zipBytes, Limits limits) throws IOException {
        try {
            return extract(zipBytes, StandardCharsets.UTF_8, limits);
        } catch (IOException | RuntimeException e) {
            if (GBK != null && isCharsetError(e)) {
                log.warn("[ZipSkillFetcher] UTF-8 entry decode failed, retrying with GBK (Windows-authored archive?)");
                return extract(zipBytes, GBK, limits);
            }
            throw e;
        }
    }

    /** True if {@code t} (or any cause) is a charset-decode failure, vs a genuine "no SKILL.md" error. */
    private static boolean isCharsetError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CharacterCodingException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && m.toLowerCase().contains("malformed")) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static ExtractedSkill extract(byte[] zipBytes, Charset charset, Limits limits) throws IOException {
        List<RawEntry> raws = new ArrayList<>();
        String skillMdContent = null;
        String skillMdPrefix = "";
        long totalSize = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName();

                // Skip macOS archive cruft so it doesn't surface as "ignored" noise.
                if (entryName.startsWith("__MACOSX/") || entryName.equals(".DS_Store")
                        || entryName.endsWith("/.DS_Store")) {
                    zis.closeEntry();
                    continue;
                }

                // Zip Slip guard: normalize and reject absolute / traversal entries.
                Path entryPath = Path.of(entryName).normalize();
                if (entryPath.isAbsolute() || entryName.contains("..")) {
                    log.warn("[ZipSkillFetcher] Skipping suspicious entry: {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                long declaredSize = entry.getSize();
                if (declaredSize > limits.maxEntryBytes()) {
                    log.warn("[ZipSkillFetcher] Skipping oversized entry: {} ({}bytes)", entryName, declaredSize);
                    zis.closeEntry();
                    continue;
                }

                byte[] bytes = zis.readAllBytes();
                if (bytes.length > limits.maxEntryBytes()) {
                    log.warn("[ZipSkillFetcher] Skipping oversized entry post-read: {} ({}bytes)", entryName, bytes.length);
                    zis.closeEntry();
                    continue;
                }
                totalSize += bytes.length;
                if (totalSize > limits.maxTotalBytes()) {
                    throw new IOException("Total extracted size exceeds " + limits.totalMb()
                            + "MB limit (adjust mateclaw.skill.upload.max-total-size-mb)");
                }

                // Skill bundles persist file contents as text (mate_skill_file
                // is a TEXT column; SkillBundle carries Map<String,String>).
                // Decoding a binary entry (.png/.woff/.zip/compiled helper, …)
                // as text replaces every invalid byte with U+FFFD, so the file
                // would be stored permanently corrupted and "restored" broken
                // on every sync. Binary resources are not supported in a bundle
                // today, so skip them with a clear WARN instead of silently
                // mangling them — matches how unknown root-level files are
                // already handled below. (Root-level binaries were already
                // dropped; this also covers binaries nested in scripts/ and
                // references/, which previously slipped through corrupted.)
                if (isLikelyBinary(bytes)) {
                    log.warn("[ZipSkillFetcher] Skipping binary entry (not supported in skill bundles): {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                String content = new String(bytes, charset);
                String normalizedName = entryPath.toString().replace('\\', '/');
                String fileName = entryPath.getFileName().toString();

                // First match wins for SKILL.md so we lock onto the shallowest one.
                if (skillMdContent == null && (SKILL_MD.equals(fileName) || SKILL_MD_LOWER.equals(fileName))) {
                    skillMdContent = content;
                    int slashIdx = normalizedName.lastIndexOf('/');
                    skillMdPrefix = slashIdx > 0 ? normalizedName.substring(0, slashIdx + 1) : "";
                    log.info("[ZipSkillFetcher] Found SKILL.md at: {}", normalizedName);
                } else {
                    raws.add(new RawEntry(normalizedName, content));
                }

                zis.closeEntry();
            }
        }

        if (skillMdContent == null) {
            throw new IllegalArgumentException("ZIP does not contain SKILL.md");
        }

        Map<String, String> references = new HashMap<>();
        Map<String, String> scripts = new HashMap<>();

        for (RawEntry raw : raws) {
            String relative = raw.name();
            if (!skillMdPrefix.isEmpty() && relative.startsWith(skillMdPrefix)) {
                relative = relative.substring(skillMdPrefix.length());
            }

            if (relative.startsWith("references/")) {
                references.put(relative.substring("references/".length()), raw.content());
            } else if (relative.startsWith("scripts/")) {
                scripts.put(relative.substring("scripts/".length()), raw.content());
            } else if (!relative.contains("/")) {
                // Sibling of SKILL.md (post-prefix-strip). Some real-world
                // packagers — notably the official tencent-meeting-mcp.zip —
                // put setup.sh at the package root instead of under scripts/.
                // Fall back to extension-based classification so those zips
                // install cleanly without forcing the user to repackage.
                String classified = classifyRootFile(relative);
                if ("scripts".equals(classified)) {
                    scripts.put(relative, raw.content());
                    log.info("[ZipSkillFetcher] Classified root-level entry '{}' as script by extension", relative);
                } else if ("references".equals(classified)) {
                    references.put(relative, raw.content());
                    log.info("[ZipSkillFetcher] Classified root-level entry '{}' as reference by extension", relative);
                } else {
                    log.warn("[ZipSkillFetcher] Ignoring root-level entry with unknown extension: {}", raw.name());
                }
            } else {
                log.warn("[ZipSkillFetcher] Ignoring entry outside references/ or scripts/: {} (skill prefix={})",
                        raw.name(), skillMdPrefix.isEmpty() ? "<root>" : skillMdPrefix);
            }
        }

        return new ExtractedSkill(skillMdContent, references, scripts);
    }

    /**
     * Heuristic binary detector: an entry is treated as binary if a NUL byte
     * (0x00) appears within the inspected prefix. UTF-8 and GBK text never
     * contain a NUL, while virtually every binary format (PNG/WOFF/ZIP/class/
     * native executable) carries one near the start — this is the same cheap,
     * reliable test git uses to decide "is this a text file". Inspecting only a
     * prefix keeps it O(1) for large entries.
     */
    private static boolean isLikelyBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0x00) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classify a root-level file (sibling of SKILL.md, no directory prefix)
     * by extension. Returns {@code "scripts"} / {@code "references"} for
     * recognized extensions, {@code null} for everything else.
     *
     * <p>Only invoked for entries that are NOT already nested under
     * {@code scripts/} or {@code references/}, so well-formed packages
     * are unaffected.
     */
    private static String classifyRootFile(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = lower.substring(dot);
        if (SCRIPT_EXTENSIONS.contains(ext)) return "scripts";
        if (REFERENCE_EXTENSIONS.contains(ext)) return "references";
        return null;
    }
}
