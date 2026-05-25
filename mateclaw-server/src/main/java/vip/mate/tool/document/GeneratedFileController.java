package vip.mate.tool.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serves bytes produced by tools and stashed in {@link GeneratedFileCache}.
 *
 * <p>Endpoint is intentionally unauthenticated; the UUID in the URL is the only
 * access credential. Entries expire after {@link GeneratedFileCache#TTL}.
 */
@Tag(name = "Generated Files")
@RestController
@RequestMapping("/api/v1/files/generated")
@RequiredArgsConstructor
public class GeneratedFileController {

    private final GeneratedFileCache cache;

    @Operation(summary = "Download a tool-generated file by its one-time id")
    @GetMapping("/{id}")
    public ResponseEntity<?> download(@PathVariable String id) {
        return cache.get(id)
                .<ResponseEntity<?>>map(entry -> {
                    String encodedName = URLEncoder.encode(entry.filename(), StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(entry.mimeType()));
                    // RFC 5987 filename* lets non-ASCII names round-trip in browsers.
                    String disposition = entry.mimeType() != null && entry.mimeType().startsWith("image/")
                            ? "inline"
                            : "attachment";
                    headers.add(HttpHeaders.CONTENT_DISPOSITION,
                            disposition + "; filename=\"" + sanitizeAscii(entry.filename())
                                    + "\"; filename*=UTF-8''" + encodedName);
                    headers.setContentLength(entry.bytes().length);
                    return ResponseEntity.ok().headers(headers).body(entry.bytes());
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "File not found or expired")));
    }

    private String sanitizeAscii(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(c < 0x20 || c >= 0x7F || c == '"' || c == '\\' ? '_' : c);
        }
        return sb.toString();
    }
}
