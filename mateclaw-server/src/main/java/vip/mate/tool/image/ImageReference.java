package vip.mate.tool.image;

/**
 * In-memory image reference used for image-edit / image-to-image generation requests.
 * <p>
 * The loader normalizes any of the agent-facing input forms (local paths, http(s)
 * URLs, {@code data:} URLs, conversation message refs) into this single shape so
 * providers receive bytes, mime type, and file name regardless of origin.
 *
 * @param data     raw image bytes
 * @param mimeType e.g. {@code image/png}
 * @param fileName logical name (best-effort, may be synthesized)
 * @param origin   trace string identifying where the bytes came from
 *                 ({@code path:/x.png}, {@code url:https://...}, {@code data-url},
 *                 {@code msg:<msgId>:<idx>}). Used for logging / audit, not
 *                 forwarded to providers.
 *
 * @author MateClaw Team
 */
public record ImageReference(byte[] data, String mimeType, String fileName, String origin) {
}
