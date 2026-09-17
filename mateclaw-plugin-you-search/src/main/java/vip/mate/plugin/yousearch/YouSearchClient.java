package vip.mate.plugin.yousearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP client for the You.com Search API.
 * <p>
 * Covers the single endpoint used by {@link YouSearchProvider}:
 * {@code POST /v1/search} with {@code X-API-Key} auth. Request body fields:
 * {@code query}, {@code count}, {@code freshness}, {@code country},
 * {@code language}, {@code safesearch}. Response shape:
 * {@code {"results":{"web":[...],"news":[...]},"metadata":{...}}} where each
 * web result carries {@code url}, {@code title}, {@code description},
 * {@code snippets} and {@code page_age}.
 *
 * <p>Failure semantics: every call either returns a parsed result or throws
 * {@link YouSearchException}. The platform's provider chain catches and falls
 * through to the next provider.
 *
 * @author Mouse Parker
 */
class YouSearchClient {

    private final YouSearchConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    YouSearchClient(YouSearchConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, config.timeoutMs())))
                .build();
    }

    /**
     * Execute a You.com web search.
     *
     * @param query     search keywords
     * @param count     max results (clamped by the platform to 1-10 before this call)
     * @param freshness optional time-range filter: day / week / month / year
     * @param language  optional BCP 47 language preference, e.g. en / zh-CN
     * @return web results, possibly empty; never null
     * @throws YouSearchException on non-2xx response or IO error
     */
    List<YouSearchHit> search(String query, Integer count, String freshness, String language) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        if (count != null) {
            body.put("count", count);
        }
        if (freshness != null && !freshness.isBlank()) {
            body.put("freshness", freshness.toLowerCase());
        }
        if (language != null && !language.isBlank()) {
            body.put("language", language);
        }
        if (config.normalizedCountry() != null) {
            body.put("country", config.normalizedCountry());
        }
        if (config.normalizedSafesearch() != null) {
            body.put("safesearch", config.normalizedSafesearch());
        }

        JsonNode resp = post("/v1/search", body);
        return parseWebResults(resp);
    }

    /**
     * Map the response onto flat hits. The You.com API returns web and news
     * sections; both share the result shape, so both are collected — web first
     * (ranked relevance), then news.
     */
    private List<YouSearchHit> parseWebResults(JsonNode resp) {
        List<YouSearchHit> out = new ArrayList<>();
        JsonNode results = resp.path("results");
        collect(results.path("web"), out);
        collect(results.path("news"), out);
        return out;
    }

    private void collect(JsonNode items, List<YouSearchHit> out) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String url = item.path("url").asText(null);
            String title = item.path("title").asText(null);
            String snippet = firstSnippet(item);
            String date = item.path("page_age").asText(null);
            if (url == null && title == null && snippet == null) {
                continue; // skip empty entries
            }
            out.add(new YouSearchHit(title, url, snippet, domainOf(url), date));
        }
    }

    /**
     * Prefer the first element of {@code snippets}; fall back to {@code description}.
     */
    private static String firstSnippet(JsonNode item) {
        JsonNode snippets = item.path("snippets");
        if (snippets.isArray() && snippets.size() > 0) {
            String s = snippets.get(0).asText(null);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return item.path("description").asText(null);
    }

    private static String domainOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shared POST helper. Returns the parsed JSON body on 2xx.
     *
     * @throws YouSearchException on non-2xx response or IO error
     */
    private JsonNode post(String path, ObjectNode body) {
        String url = config.normalizedBaseUrl() + path;
        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new YouSearchException("You.com " + path + " returned HTTP " + code
                        + ": " + truncate(resp.body(), 500));
            }
            return mapper.readTree(resp.body() == null ? "{}" : resp.body());
        } catch (YouSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new YouSearchException("You.com " + path + " request failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
