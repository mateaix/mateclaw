package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Normalizes the raw {@code mate_message.metadata} column value into parseable JSON.
 * <p>
 * The column is declared {@code JSON}. Read back through MyBatis, H2 hands it
 * over as a JSON <em>string literal</em> — the whole document quoted and
 * escaped — while MySQL and PostgreSQL return the object text directly. Code
 * that parses the raw value therefore works in production and quietly stops
 * working on the desktop/dev H2 profile.
 * <p>
 * The failure is always silent, never an exception the caller notices:
 * <ul>
 *   <li>{@code readTree} yields a {@code TextNode}, so every field lookup misses
 *       and the metadata reads as absent rather than as unparsed;</li>
 *   <li>{@code readValue(.., Map.class)} throws, and these call sites all sit
 *       inside a best-effort {@code catch} that degrades instead of failing;</li>
 *   <li>a regex over the raw text stops matching, because {@code "key":"value"}
 *       has become {@code \"key\":\"value\"} — the key still greps, so a
 *       {@code contains} guard passes and only the extraction comes up empty.</li>
 * </ul>
 * Call {@link #normalize(String)} before parsing or matching.
 *
 * @author MateClaw Team
 */
public final class MessageMetadataJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageMetadataJson() {
    }

    /**
     * Return the metadata as plain JSON text, unwrapping one layer of string
     * encoding when present. Returns the input unchanged when it is already
     * plain JSON, blank, or not decodable — callers keep their existing
     * behaviour for values this cannot improve.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String json = raw.trim();
        if (json.length() < 2 || json.charAt(0) != '"' || json.charAt(json.length() - 1) != '"') {
            return raw;
        }
        try {
            String unwrapped = MAPPER.readValue(json, String.class);
            return unwrapped != null ? unwrapped : raw;
        } catch (Exception e) {
            // Not a JSON string literal after all (e.g. truncated). Hand back
            // the original so the caller's own error handling decides.
            return raw;
        }
    }
}
