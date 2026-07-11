package vip.mate.channel.webchat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Storage + validation for files exchanged over the WebChat channel.
 *
 * <p>WebChat is reached by untrusted external visitors (API key + visitor
 * token, no JWT), so uploads are hardened here: size cap, extension allow-list,
 * filename sanitization, and a server-issued stored name. Every path is derived
 * from the server-computed {@code conversationId} — never from a client-supplied
 * path — and download resolution is traversal-guarded.
 *
 * <p>Uploads are staged in an in-memory registry keyed by an opaque file id.
 * Only when the visitor references that id on the next {@code /stream} call does
 * the file become a real conversation attachment (the bytes already live under
 * the conversation's upload dir, so cleanup rides the existing
 * {@code cleanAttachmentFiles} cascade). Unreferenced staged files are swept
 * after {@link #STAGING_TTL_MS}.
 */
@Slf4j
@Service
public class WebChatFileService {

    /** How long an uploaded-but-unreferenced file lingers before the sweep removes it. */
    private static final long STAGING_TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final boolean enabled;
    private final long maxSizeBytes;
    private final Set<String> allowedExtensions;
    private final int maxFilesPerConversation;
    private final long maxTotalBytesPerConversation;
    private final ChatUploadLocationResolver uploadLocationResolver;

    /** fileId (== storedName) -> staged metadata, pending a /stream reference. */
    private final ConcurrentHashMap<String, StagedFile> staged = new ConcurrentHashMap<>();

    public WebChatFileService(
            @Value("${mateclaw.webchat.upload.enabled:true}") boolean enabled,
            @Value("${mateclaw.webchat.upload.max-size-mb:20}") long maxSizeMb,
            @Value("${mateclaw.webchat.upload.allowed-extensions:"
                    + "png,jpg,jpeg,gif,webp,bmp,pdf,txt,md,csv,json,log,"
                    + "doc,docx,xls,xlsx,ppt,pptx,zip,mp3,wav,m4a,mp4,mov,webm}") String allowedExtensionsCsv,
            @Value("${mateclaw.webchat.upload.max-files-per-conversation:50}") int maxFilesPerConversation,
            @Value("${mateclaw.webchat.upload.max-total-mb-per-conversation:200}") long maxTotalMbPerConversation,
            ChatUploadLocationResolver uploadLocationResolver) {
        this.enabled = enabled;
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
        this.allowedExtensions = Arrays.stream(allowedExtensionsCsv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.maxFilesPerConversation = maxFilesPerConversation;
        this.maxTotalBytesPerConversation = maxTotalMbPerConversation * 1024 * 1024;
        this.uploadLocationResolver = uploadLocationResolver;
    }

    /** Metadata for a staged upload. */
    public record StagedFile(String conversationId, String storedName, String originalName,
                             String contentType, long size, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** Thrown on any validation failure; the controller maps it to a 4xx. */
    public static class UploadRejectedException extends RuntimeException {
        public UploadRejectedException(String message) {
            super(message);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Validate and store an uploaded file under the conversation's upload dir,
     * returning a staged record whose {@code storedName} doubles as the opaque
     * file id the visitor references on the next /stream call.
     *
     * @param conversationId server-derived conversation id (never client-supplied)
     */
    public StagedFile store(String conversationId, MultipartFile file) throws IOException {
        if (!enabled) {
            throw new UploadRejectedException("WebChat file upload is disabled");
        }
        if (file == null || file.isEmpty()) {
            throw new UploadRejectedException("Empty file");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new UploadRejectedException("File too large (max " + (maxSizeBytes / 1024 / 1024) + " MB)");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        // Strip any directory components, then collapse to a safe charset.
        String baseName = Path.of(originalName).getFileName().toString();
        String ext = extensionOf(baseName);
        if (ext.isEmpty() || !allowedExtensions.contains(ext)) {
            throw new UploadRejectedException("File type not allowed: ." + ext);
        }
        String safeName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

        String storedName = UUID.randomUUID() + "_" + safeName;
        Path uploadRoot = uploadLocationResolver.resolveUploadRoot(conversationId).normalize();
        // Sanitize the id for the path segment (IM ids like "wecom:XXXX" carry a
        // ':' illegal on Windows); reads use the same sanitization.
        Path dir = uploadRoot.resolve(ChatUploadLocationResolver.sanitizeSegment(conversationId)).normalize();
        if (!dir.startsWith(uploadRoot)) {
            // conversationId is server-derived, so this should never happen; fail closed if it does.
            throw new UploadRejectedException("Invalid conversation");
        }
        Files.createDirectories(dir);
        enforceConversationQuota(dir, file.getSize());
        Path target = dir.resolve(storedName);
        file.transferTo(target.toAbsolutePath());

        String contentType = Optional.ofNullable(file.getContentType())
                .filter(ct -> !ct.isBlank())
                .orElseGet(() -> probe(target));

        StagedFile entry = new StagedFile(conversationId, storedName, baseName, contentType,
                file.getSize(), System.currentTimeMillis() + STAGING_TTL_MS);
        staged.put(storedName, entry);
        log.info("[webchat-file] Stored upload conv={} stored={} type={} size={}",
                conversationId, storedName, contentType, file.getSize());
        return entry;
    }

    /**
     * Resolve a staged file id into its metadata, asserting it belongs to this
     * conversation and has not expired. Consuming it removes the staging entry
     * (the bytes remain as a committed conversation attachment). Returns empty
     * if the id is unknown, expired, or belongs to another conversation.
     */
    public Optional<StagedFile> consume(String conversationId, String fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        StagedFile entry = staged.get(fileId);
        if (entry == null || entry.expired() || !entry.conversationId().equals(conversationId)) {
            return Optional.empty();
        }
        staged.remove(fileId);
        return Optional.of(entry);
    }

    /**
     * Traversal-safe resolution of a stored file under the conversation's dir.
     * Both the dir and the final path are derived from the server-computed
     * conversationId; the client-supplied {@code storedName} is confined by the
     * {@code startsWith} guard. Returns empty if missing or escaping the dir.
     */
    public Optional<Path> resolve(String conversationId, String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return Optional.empty();
        }
        // Check every candidate root (workspace-scoped dir + legacy default dir)
        // so files written before the workspace-aware relocation still resolve.
        for (Path base : uploadLocationResolver.resolveCandidateConversationDirs(conversationId)) {
            Path normBase = base.normalize();
            Path file = normBase.resolve(storedName).normalize();
            if (file.startsWith(normBase) && Files.exists(file) && Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /** Map a content type to the MessageContentPart type the agent/UI understands. */
    public static String partTypeFor(String contentType) {
        if (contentType == null) {
            return "file";
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) return "image";
        if (ct.startsWith("video/")) return "video";
        if (ct.startsWith("audio/")) return "audio";
        return "file";
    }

    /** Periodically drop staged files the visitor never referenced. */
    @Scheduled(fixedDelay = 15 * 60 * 1000L)
    public void sweepExpired() {
        staged.values().removeIf(entry -> {
            if (!entry.expired()) {
                return false;
            }
            resolve(entry.conversationId(), entry.storedName()).ifPresent(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[webchat-file] Failed to delete expired staged file {}: {}",
                            p, e.getMessage());
                }
            });
            return true;
        });
    }

    /**
     * Bound a conversation's disk footprint: reject when the dir already holds
     * the max file count, or when adding {@code incomingSize} would push the
     * total over the cap. Cheap dir scan (these dirs hold at most a few dozen
     * files); pairs with the staging TTL sweep that reclaims unreferenced files.
     */
    private void enforceConversationQuota(Path dir, long incomingSize) throws IOException {
        int count = 0;
        long total = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p)) {
                    count++;
                    total += Files.size(p);
                }
            }
        }
        if (count >= maxFilesPerConversation) {
            throw new UploadRejectedException(
                    "Too many files in this conversation (max " + maxFilesPerConversation + ")");
        }
        if (total + incomingSize > maxTotalBytesPerConversation) {
            throw new UploadRejectedException(
                    "Conversation upload quota exceeded (max "
                            + (maxTotalBytesPerConversation / 1024 / 1024) + " MB)");
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String probe(Path path) {
        try {
            String ct = Files.probeContentType(path);
            return ct != null ? ct : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
