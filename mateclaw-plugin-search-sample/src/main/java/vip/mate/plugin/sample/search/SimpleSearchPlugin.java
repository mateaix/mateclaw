package vip.mate.plugin.sample.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample plugin demonstrating {@code PluginType.SEARCH}: registers a search
 * provider that queries a configurable JSON endpoint. Expected response shape:
 * {@code {"results":[{"title":"...","url":"...","snippet":"..."}]}}
 *
 * @author MateClaw Team
 */
public class SimpleSearchPlugin implements MateClawPlugin {

    private Logger log;

    @Override
    public void onLoad(PluginContext context) {
        this.log = context.getLogger();
        context.registerSearchProvider(new DemoSearchProvider(context));
        log.info("SimpleSearchPlugin loaded, search provider registered");
    }

    @Override
    public void onEnable() {
        if (log != null) log.info("SimpleSearchPlugin enabled");
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("SimpleSearchPlugin disabled");
    }

    static class DemoSearchProvider implements PluginSearchProvider {

        private static final Duration TIMEOUT = Duration.ofSeconds(15);

        private final PluginContext context;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        private final ObjectMapper objectMapper = new ObjectMapper();

        DemoSearchProvider(PluginContext context) {
            this.context = context;
        }

        @Override
        public String id() {
            return "demo-search";
        }

        @Override
        public String label() {
            return "Demo Search";
        }

        @Override
        public boolean isAvailable() {
            String baseUrl = context.getConfig("baseUrl", String.class);
            return baseUrl != null && !baseUrl.isBlank();
        }

        @Override
        public List<PluginSearchResult> search(PluginSearchQuery query) {
            String baseUrl = context.getConfig("baseUrl", String.class);
            String apiKey = context.getConfig("apiKey", String.class);

            String url = baseUrl + (baseUrl.contains("?") ? "&" : "?")
                    + "q=" + URLEncoder.encode(query.query(), StandardCharsets.UTF_8)
                    + "&count=" + query.count();

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            try {
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("Search endpoint returned HTTP " + resp.statusCode());
                }
                return parse(resp.body());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Search request failed: " + e.getMessage(), e);
            }
        }

        private List<PluginSearchResult> parse(String body) throws Exception {
            List<PluginSearchResult> results = new ArrayList<>();
            JsonNode items = objectMapper.readTree(body).path("results");
            for (JsonNode item : items) {
                results.add(new PluginSearchResult(
                        item.path("title").asText(null),
                        item.path("url").asText(null),
                        item.path("snippet").asText(null),
                        null,
                        null));
            }
            return results;
        }
    }
}
