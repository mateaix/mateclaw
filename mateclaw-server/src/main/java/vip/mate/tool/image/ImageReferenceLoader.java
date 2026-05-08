package vip.mate.tool.image;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Resolves agent-supplied image reference strings into in-memory
 * {@link ImageReference} buffers. Five input forms are accepted:
 *
 * <ol>
 *   <li>Local filesystem path: {@code /abs/path.png}, {@code ./rel.png},
 *       {@code ~/x.png}, or {@code file://...}.</li>
 *   <li>Data URL: {@code data:image/png;base64,...} (base64 or URL-encoded body).</li>
 *   <li>HTTP(S) URL: downloaded with size + content-type guard.</li>
 *   <li>Conversation message reference: {@code msg:<messageId>:<partIndex>} —
 *       resolves to the local path stored on a {@link MessageContentPart} of
 *       type {@code image} on the named message. This is the channel an agent
 *       uses to forward a user-uploaded image into the image edit tool, so a
 *       non-vision model can still operate on attachments it cannot "see".</li>
 *   <li>Workspace-relative path: passed through as a regular path; the caller
 *       is expected to anchor it to the active workspace before invocation.</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageReferenceLoader {

    private static final long MAX_REFERENCE_BYTES = 20L * 1024 * 1024;
    private static final int HTTP_TIMEOUT_MS = 30_000;

    private final ConversationService conversationService;

    /**
     * Resolve a list of input strings; null / blank entries are skipped.
     * The caller is expected to enforce per-provider {@code maxInputImages}
     * before calling.
     */
    public List<ImageReference> loadAll(List<String> inputs, String conversationId) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<ImageReference> out = new ArrayList<>(inputs.size());
        for (String raw : inputs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            out.add(load(raw.trim(), conversationId));
        }
        return out;
    }

    /** Resolve a single reference string. */
    public ImageReference load(String input, String conversationId) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IOException("image reference is blank");
        }
        String trimmed = input.trim();

        if (trimmed.startsWith("data:")) {
            return loadDataUrl(trimmed);
        }
        if (trimmed.startsWith("msg:")) {
            return loadConversationMessageRef(trimmed, conversationId);
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return loadHttpUrl(trimmed);
        }
        return loadFilePath(trimmed);
    }

    // ==================== form: local path / file:// ====================

    private ImageReference loadFilePath(String input) throws IOException {
        String pathStr = input.startsWith("file://") ? input.substring("file://".length()) : input;
        if (pathStr.startsWith("~")) {
            pathStr = System.getProperty("user.home") + pathStr.substring(1);
        }
        Path p = Paths.get(pathStr);
        if (!Files.exists(p)) {
            throw new IOException("Image file not found: " + pathStr);
        }
        if (Files.size(p) > MAX_REFERENCE_BYTES) {
            throw new IOException("Image exceeds 20MB limit: " + pathStr);
        }
        byte[] data = Files.readAllBytes(p);
        String mime = inferMimeFromName(p.getFileName().toString());
        return new ImageReference(data, mime, p.getFileName().toString(), "path:" + p);
    }

    // ==================== form: data: URL ====================

    private ImageReference loadDataUrl(String dataUrl) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IOException("Malformed data URL: missing comma");
        }
        String header = dataUrl.substring("data:".length(), comma);
        String body = dataUrl.substring(comma + 1);
        boolean isBase64 = header.toLowerCase().contains(";base64");
        String mime = isBase64
                ? header.substring(0, header.toLowerCase().indexOf(";base64"))
                : (header.contains(";") ? header.substring(0, header.indexOf(';')) : header);
        if (mime == null || mime.isBlank()) {
            mime = "image/png";
        }
        byte[] data;
        try {
            data = isBase64
                    ? Base64.getDecoder().decode(body)
                    : URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid base64 in data URL: " + e.getMessage(), e);
        }
        if (data.length > MAX_REFERENCE_BYTES) {
            throw new IOException("Image exceeds 20MB limit (data URL)");
        }
        return new ImageReference(data, mime, "inline." + extensionFor(mime), "data-url");
    }

    // ==================== form: http(s) URL ====================

    private ImageReference loadHttpUrl(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URL has no host: " + url);
        }
        // Conservative SSRF guard: reject obvious internal targets. Refine later
        // if the project gains a dedicated SsrFPolicy module.
        String lowered = host.toLowerCase();
        if (lowered.equals("localhost")
                || lowered.equals("127.0.0.1")
                || lowered.startsWith("10.")
                || lowered.startsWith("192.168.")
                || lowered.startsWith("169.254.")
                || lowered.startsWith("172.")) {
            throw new IOException("Refusing to download image from internal host: " + host);
        }
        try {
            byte[] data = HttpUtil.createGet(url).timeout(HTTP_TIMEOUT_MS).execute().bodyBytes();
            if (data == null || data.length == 0) {
                throw new IOException("Empty response downloading image from " + url);
            }
            if (data.length > MAX_REFERENCE_BYTES) {
                throw new IOException("Image exceeds 20MB limit: " + url);
            }
            String fileName = guessFileNameFromUrl(url);
            String mime = inferMimeFromName(fileName);
            return new ImageReference(data, mime, fileName, "url:" + url);
        } catch (Exception e) {
            throw new IOException("Failed to download image " + url + ": " + e.getMessage(), e);
        }
    }

    // ==================== form: msg:<messageId>:<partIndex> ====================

    private ImageReference loadConversationMessageRef(String ref, String conversationId) throws IOException {
        // ref shape: "msg:<messageId>" (first image part) or "msg:<messageId>:<partIndex>"
        String body = ref.substring("msg:".length());
        String[] parts = body.split(":", 2);
        long messageId;
        try {
            messageId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid msg: ref, expected msg:<messageId>[:<idx>]: " + ref);
        }
        Integer wantedIdx = null;
        if (parts.length == 2 && !parts[1].isBlank()) {
            try {
                wantedIdx = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid part index in: " + ref);
            }
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IOException("Cannot resolve msg: reference without an active conversation");
        }
        MessageEntity message = findMessageInConversation(conversationId, messageId);
        if (message == null) {
            throw new IOException("Message " + messageId + " not found in conversation " + conversationId);
        }
        List<MessageContentPart> contentParts = conversationService.parseMessageParts(message);
        MessageContentPart picked = pickImagePart(contentParts, wantedIdx);
        if (picked == null) {
            throw new IOException("No image part on message " + messageId
                    + (wantedIdx != null ? " at index " + wantedIdx : ""));
        }
        Path filePath = resolveLocalPath(picked);
        if (filePath == null) {
            throw new IOException("Message " + messageId + " image part has no local path: "
                    + picked.getFileName());
        }
        if (Files.size(filePath) > MAX_REFERENCE_BYTES) {
            throw new IOException("Image exceeds 20MB limit: " + filePath);
        }
        byte[] data = Files.readAllBytes(filePath);
        String mime = picked.getContentType();
        if (mime == null || mime.isBlank() || "image/*".equals(mime)) {
            mime = inferMimeFromName(picked.getFileName());
        }
        String fileName = picked.getFileName() != null ? picked.getFileName() : filePath.getFileName().toString();
        return new ImageReference(data, mime, fileName, ref);
    }

    private MessageEntity findMessageInConversation(String conversationId, long messageId) {
        List<MessageEntity> all = conversationService.listMessages(conversationId);
        for (MessageEntity m : all) {
            if (m.getId() != null && m.getId() == messageId) {
                return m;
            }
        }
        return null;
    }

    private MessageContentPart pickImagePart(List<MessageContentPart> parts, Integer wantedIdx) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        if (wantedIdx != null) {
            int seen = 0;
            for (MessageContentPart p : parts) {
                if (p == null || !"image".equals(p.getType())) continue;
                if (seen == wantedIdx) {
                    return p;
                }
                seen++;
            }
            return null;
        }
        for (MessageContentPart p : parts) {
            if (p != null && "image".equals(p.getType())) {
                return p;
            }
        }
        return null;
    }

    private Path resolveLocalPath(MessageContentPart part) {
        if (part.getPath() != null && !part.getPath().isBlank()) {
            Path p = Paths.get(part.getPath());
            if (Files.exists(p)) return p;
        }
        if (part.getStoredName() != null && !part.getStoredName().isBlank()) {
            Path p = Paths.get(part.getStoredName());
            if (Files.exists(p)) return p;
        }
        return null;
    }

    // ==================== shared helpers ====================

    private static String inferMimeFromName(String name) {
        if (name == null) return "image/png";
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private static String extensionFor(String mime) {
        return switch (mime.toLowerCase().trim()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }

    private static String guessFileNameFromUrl(String url) {
        String stripped = url.split("\\?", 2)[0];
        int slash = stripped.lastIndexOf('/');
        String tail = slash >= 0 ? stripped.substring(slash + 1) : stripped;
        return tail.isBlank() ? "remote.png" : tail;
    }
}
