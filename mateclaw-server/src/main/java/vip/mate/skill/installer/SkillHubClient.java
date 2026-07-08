package vip.mate.skill.installer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.skill.installer.model.HubSkillInfo;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ClawHub marketplace API client.
 * <p>
 * Talks to two endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/search?q=&limit=} — returns {@code {results: [{slug, displayName, summary, version, ...}]}}</li>
 *   <li>{@code GET /api/v1/skills/{slug}} — returns {@code {skill: {...}, latestVersion: {...}, owner: {...}}}</li>
 *   <li>{@code GET /api/v1/download?slug=&version=} — returns the bundle as a ZIP (application/zip)</li>
 * </ul>
 * The bundle's SKILL.md is delivered via the ZIP, not embedded in the metadata
 * response, which is why an earlier "expect a {@code content} field on the
 * skill JSON" implementation always saw empty content and aborted installs.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class SkillHubClient {

    /** Per-entry / total caps for marketplace bundle ZIPs — same knobs as upload. */
    @Value("${mateclaw.skill.upload.max-entry-size-mb:1}")
    private long maxEntrySizeMb = 1;

    @Value("${mateclaw.skill.upload.max-total-size-mb:50}")
    private long maxTotalSizeMb = 50;

    private final SkillHubProperties properties;
    private final ObjectMapper objectMapper;
    private final SkillFrontmatterParser frontmatterParser;
    private final HttpClient httpClient;

    public SkillHubClient(SkillHubProperties properties,
                          ObjectMapper objectMapper,
                          SkillFrontmatterParser frontmatterParser) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.frontmatterParser = frontmatterParser;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getHttpTimeout()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Search the ClawHub marketplace.
     */
    public List<HubSkillInfo> search(String query, int limit) {
        String url = properties.getBaseUrl() + properties.getSearchPath()
                + "?q=" + encodeParam(query) + "&limit=" + limit;

        for (int attempt = 0; attempt <= properties.getHttpRetries(); attempt++) {
            try {
                HttpRequest request = jsonGet(url);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseSearchResponse(response.body());
                }

                if (isRetryable(response.statusCode()) && attempt < properties.getHttpRetries()) {
                    log.warn("Hub search attempt {} failed with status {}, retrying...", attempt + 1, response.statusCode());
                    Thread.sleep(backoffMs(attempt));
                    continue;
                }

                log.warn("Hub search failed with status {}: {}", response.statusCode(), response.body());
                return Collections.emptyList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            } catch (Exception e) {
                if (attempt < properties.getHttpRetries()) {
                    log.warn("Hub search attempt {} error: {}, retrying...", attempt + 1, e.getMessage());
                    try {
                        Thread.sleep(backoffMs(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Collections.emptyList();
                    }
                } else {
                    log.error("Hub search failed after {} attempts: {}", properties.getHttpRetries() + 1, e.getMessage());
                }
            }
        }
        return Collections.emptyList();
    }

    /**
     * Fetch a skill bundle from ClawHub.
     * <p>
     * Two-step fetch: (1) GET metadata to learn version + author/icon
     * defaults; (2) GET the ZIP and decompress in memory. The two requests
     * share the same retry policy as {@link #search}.
     * <p>
     * Returns {@code null} if the bundle ZIP can't be downloaded or doesn't
     * contain a SKILL.md — never returns a SkillBundle with empty content,
     * since reinstalling that would wipe the user's local SKILL.md.
     */
    public SkillBundle fetchBundle(String slug, String version) {
        if (slug == null || slug.isBlank()) {
            log.warn("fetchBundle called with blank slug");
            return null;
        }

        // Step 1: best-effort metadata lookup. Failure isn't fatal — the ZIP
        // alone is enough to install, but metadata gives us author / icon
        // / latest version when SKILL.md frontmatter omits them.
        HubSkillMetadata metadata = fetchMetadata(slug);
        String resolvedVersion = (version != null && !version.isBlank())
                ? version
                : (metadata != null ? metadata.version() : null);

        // Step 2: download the ZIP.
        byte[] zipBytes = downloadBundleZip(slug, resolvedVersion);
        if (zipBytes == null) {
            return null;
        }

        // Step 3: extract + assemble SkillBundle.
        try {
            ZipSkillFetcher.ExtractedSkill extracted = ZipSkillFetcher.extract(
                    new ByteArrayInputStream(zipBytes),
                    ZipSkillFetcher.Limits.ofMb(maxEntrySizeMb, maxTotalSizeMb));

            var parsed = frontmatterParser.parse(extracted.skillMdContent());
            Map<String, Object> fm = parsed.getFrontmatter();

            String name = firstNonBlank(parsed.getName(),
                    metadata != null ? metadata.displayName() : null,
                    slug);
            String description = firstNonBlank(parsed.getDescription(),
                    metadata != null ? metadata.summary() : null,
                    "");
            String resolvedVer = firstNonBlank(
                    fm != null && fm.get("version") != null ? String.valueOf(fm.get("version")) : null,
                    resolvedVersion,
                    metadata != null ? metadata.version() : null,
                    "1.0.0");
            String author = firstNonBlank(
                    fm != null && fm.get("author") != null ? String.valueOf(fm.get("author")) : null,
                    metadata != null ? metadata.owner() : null,
                    "");
            String icon = firstNonBlank(
                    fm != null && fm.get("icon") != null ? String.valueOf(fm.get("icon")) : null,
                    "📦");

            String sourceUrl = properties.getBaseUrl() + "/skills/" + slug
                    + (resolvedVer != null && !resolvedVer.isBlank() ? "@" + resolvedVer : "");

            return new SkillBundle(
                    name,
                    extracted.skillMdContent(),
                    extracted.references(),
                    extracted.scripts(),
                    "clawhub",
                    sourceUrl,
                    resolvedVer,
                    description,
                    author,
                    icon
            );
        } catch (Exception e) {
            log.warn("Failed to parse hub bundle ZIP for '{}': {}", slug, e.getMessage());
            return null;
        }
    }

    // ==================== HTTP fetch helpers ====================

    private HubSkillMetadata fetchMetadata(String slug) {
        String url = properties.getBaseUrl() + properties.getSkillsPath() + "/" + encodeParam(slug);
        for (int attempt = 0; attempt <= properties.getHttpRetries(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(jsonGet(url), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return parseMetadataResponse(response.body());
                }
                if (status == 404) {
                    log.info("Hub metadata not found for '{}'", slug);
                    return null;
                }
                if (isRetryable(status) && attempt < properties.getHttpRetries()) {
                    log.warn("Hub metadata attempt {} for '{}' failed with status {}, retrying...", attempt + 1, slug, status);
                    Thread.sleep(backoffMs(attempt));
                    continue;
                }
                log.warn("Hub metadata fetch failed for '{}': status {}", slug, status);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                if (attempt < properties.getHttpRetries()) {
                    log.warn("Hub metadata attempt {} for '{}' error: {}, retrying...", attempt + 1, slug, e.getMessage());
                    try {
                        Thread.sleep(backoffMs(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.warn("Hub metadata fetch failed after {} attempts for '{}': {}", properties.getHttpRetries() + 1, slug, e.getMessage());
                }
            }
        }
        return null;
    }

    private byte[] downloadBundleZip(String slug, String version) {
        StringBuilder url = new StringBuilder()
                .append(properties.getBaseUrl())
                .append(properties.getDownloadPath())
                .append("?slug=").append(encodeParam(slug));
        if (version != null && !version.isBlank()) {
            url.append("&version=").append(encodeParam(version));
        }

        for (int attempt = 0; attempt <= properties.getHttpRetries(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(properties.getHttpTimeout()))
                        .GET()
                        .header("Accept", "application/zip, application/octet-stream")
                        .header("User-Agent", "MateClaw/1.0")
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 200) {
                    byte[] body = response.body();
                    if (body == null || body.length == 0) {
                        log.warn("Hub download for '{}' returned empty body; treating as failure to avoid wiping local SKILL.md", slug);
                        return null;
                    }
                    return body;
                }

                if (isRetryable(status) && attempt < properties.getHttpRetries()) {
                    log.warn("Hub download attempt {} for '{}' failed with status {}, retrying...", attempt + 1, slug, status);
                    Thread.sleep(backoffMs(attempt));
                    continue;
                }

                log.warn("Hub download failed for '{}': status {}", slug, status);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                if (attempt < properties.getHttpRetries()) {
                    log.warn("Hub download attempt {} for '{}' error: {}, retrying...", attempt + 1, slug, e.getMessage());
                    try {
                        Thread.sleep(backoffMs(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.error("Hub download failed after {} attempts for '{}': {}", properties.getHttpRetries() + 1, slug, e.getMessage());
                }
            }
        }
        return null;
    }

    private HttpRequest jsonGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getHttpTimeout()))
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "MateClaw/1.0")
                .build();
    }

    // ==================== JSON parsing ====================

    private List<HubSkillInfo> parseSearchResponse(String body) {
        try {
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {});
            Object data = json.get("results");
            if (data == null) {
                data = json.get("data");
            }
            if (data == null) {
                data = json.get("skills");
            }
            if (data instanceof List<?> list) {
                String jsonStr = objectMapper.writeValueAsString(list);
                return objectMapper.readValue(jsonStr, new TypeReference<>() {});
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to parse hub search response: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse the metadata payload returned by {@code /api/v1/skills/{slug}}.
     * Tolerates both the current nested shape ({@code {skill, latestVersion, owner}})
     * and a flat shape (some self-hosted hubs).
     */
    @SuppressWarnings("unchecked")
    private HubSkillMetadata parseMetadataResponse(String body) {
        try {
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {});

            Map<String, Object> skill = json.get("skill") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : json;

            String displayName = stringOf(skill.get("displayName"));
            if (displayName == null) displayName = stringOf(skill.get("name"));
            String summary = stringOf(skill.get("summary"));
            if (summary == null) summary = stringOf(skill.get("description"));

            String version = null;
            if (json.get("latestVersion") instanceof Map<?, ?> lv) {
                version = stringOf(((Map<String, Object>) lv).get("version"));
            }
            if (version == null) version = stringOf(skill.get("version"));

            String owner = null;
            if (json.get("owner") instanceof Map<?, ?> ownerMap) {
                Map<String, Object> o = (Map<String, Object>) ownerMap;
                owner = firstNonBlank(stringOf(o.get("displayName")), stringOf(o.get("handle")));
            }
            if (owner == null) owner = stringOf(skill.get("author"));

            return new HubSkillMetadata(displayName, summary, version, owner);
        } catch (Exception e) {
            log.warn("Failed to parse hub metadata response: {}", e.getMessage());
            return null;
        }
    }

    private record HubSkillMetadata(String displayName, String summary, String version, String owner) {}

    // ==================== misc helpers ====================

    private static String stringOf(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private long backoffMs(int attempt) {
        return (long) (800 * Math.pow(2, attempt));
    }

    private String encodeParam(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
