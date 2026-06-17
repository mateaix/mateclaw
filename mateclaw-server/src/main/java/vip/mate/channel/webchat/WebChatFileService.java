package vip.mate.channel.webchat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    /** Shared with the JWT chat upload dir so deleteConversation cleanup applies. */
    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    /** How long an uploaded-but-unreferenced file lingers before the sweep removes it. */
    private static final long STAGING_TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final boolean enabled;
    private final long maxSizeBytes;
    private final Set<String> allowedExtensions;

    /** fileId (== storedName) -> staged metadata, pending a /stream reference. */
    private final ConcurrentHashMap<String, StagedFile> staged = new ConcurrentHashMap<>();

    public WebChatFileService(
            @Value("${mateclaw.webchat.upload.enabled:true}") boolean enabled,
            @Value("${mateclaw.webchat.upload.max-size-mb:20}") long maxSizeMb,
            @Value("${mateclaw.webchat.upload.allowed-extensions:"
                    + "png,jpg,jpeg,gif,webp,bmp,pdf,txt,md,csv,json,log,"
                    + "doc,docx,xls,xlsx,ppt,pptx,zip,mp3,wav,m4a,mp4,mov,webm}") String allowedExtensionsCsv) {
        this.enabled = enabled;
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
        this.allowedExtensions = Arrays.stream(allowedExtensionsCsv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
        String baseName = Paths.get(originalName).getFileName().toString();
        String ext = extensionOf(baseName);
        if (ext.isEmpty() || !allowedExtensions.contains(ext)) {
            throw new UploadRejectedException("File type not allowed: ." + ext);
        }
        String safeName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

        String storedName = UUID.randomUUID() + "_" + safeName;
        Path dir = UPLOAD_ROOT.resolve(conversationId).normalize();
        if (!dir.startsWith(UPLOAD_ROOT.normalize())) {
            // conversationId is server-derived, so this should never happen; fail closed if it does.
            throw new UploadRejectedException("Invalid conversation");
        }
        Files.createDirectories(dir);
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
        Path base = UPLOAD_ROOT.resolve(conversationId).normalize();
        Path file = base.resolve(storedName).normalize();
        if (!file.startsWith(base) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
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
