package vip.mate.llm.failover.probe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import vip.mate.llm.chatmodel.OpenAiModelsPath;
import vip.mate.llm.failover.ProbeResult;
import vip.mate.llm.failover.ProviderProbeStrategy;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Probes an OpenAI-compatible provider by listing its models.
 *
 * <p>Two non-trivial things this implementation handles:</p>
 *
 * <ol>
 *   <li><b>Path construction.</b> Delegated to {@link OpenAiModelsPath} so the
 *       probe lists the exact same endpoint discovery does — including an
 *       operator's {@code modelsPath} override for non-standard gateway prefixes.
 *       Without sharing, a provider could be discoverable yet still marked
 *       unhealthy here by a probe hitting the wrong hard-coded path.</li>
 *
 *   <li><b>Permissive 4xx/5xx handling.</b> Not every OpenAI-compatible
 *       vendor implements {@code /models}. Kimi for Coding returns a
 *       structured 404 here even though chat works fine. So we treat
 *       {@code 404 / 405 / 410} as <i>fail-open</i> (probe inconclusive,
 *       leave the provider in the pool — first chat call will be authoritative)
 *       rather than HARD removing. Only auth (401/403) and connection-level
 *       failures (DNS / refused / timeout) are treated as definitive negatives.</li>
 * </ol>
 */
@Slf4j
@Component
public class OpenAiCompatibleListModelsProbe implements ProviderProbeStrategy {

    /** Conservative HTTP timeout — keeps a stalled provider from holding up the parallel batch. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    public OpenAiCompatibleListModelsProbe(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        if (provider == null || !StringUtils.hasText(provider.getBaseUrl())) {
            return ProbeResult.fail(0, "base URL not configured");
        }
        String baseUrl = stripTrailingSlash(provider.getBaseUrl().trim());
        String modelsPath = OpenAiModelsPath.resolve(baseUrl, parseKwargs(provider.getGenerateKwargs()));
        long start = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            RestClient.RequestHeadersSpec<?> spec = client.get().uri(modelsPath);
            String apiKey = provider.getApiKey();
            if (StringUtils.hasText(apiKey)) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
            }

            String body = spec.retrieve().body(String.class);
            long latency = System.currentTimeMillis() - start;
            if (body == null || body.isBlank()) {
                // Empty body from a 200 — unusual but not necessarily fatal; treat as ok.
                log.debug("[Probe] {} {} returned empty body — treating as ok",
                        provider.getProviderId(), modelsPath);
                return ProbeResult.ok(latency);
            }
            return ProbeResult.ok(latency);
        } catch (HttpClientErrorException e) {
            long latency = System.currentTimeMillis() - start;
            int status = e.getStatusCode().value();
            // 401/403 = real auth failure → HARD remove
            if (status == 401 || status == 403) {
                log.debug("[Probe] {} {} auth failed: {}", provider.getProviderId(), modelsPath, status);
                return ProbeResult.fail(latency, "auth failed (" + status + ")");
            }
            // 404/405/410 = endpoint not implemented — many providers (e.g. Kimi for Coding)
            // don't expose /v1/models even though chat works. Stay in pool, let the chat
            // path be authoritative; the worst case is one wasted first request.
            if (status == 404 || status == 405 || status == 410) {
                log.info("[Probe] {} {} returned {} — endpoint not implemented; treating as fail-open (in-pool)",
                        provider.getProviderId(), modelsPath, status);
                return ProbeResult.ok(latency);
            }
            // Other 4xx (e.g., 400 invalid request, 429 rate limit on the probe itself):
            // ambiguous — fail-open too, since these don't tell us about chat capability.
            log.info("[Probe] {} {} returned {} — fail-open (inconclusive)",
                    provider.getProviderId(), modelsPath, status);
            return ProbeResult.ok(latency);
        } catch (HttpServerErrorException e) {
            long latency = System.currentTimeMillis() - start;
            // 5xx — the provider's listing infrastructure is flaky but chat may still work.
            // Fail-open and let runtime cooldown handle it if chat also fails.
            log.info("[Probe] {} {} returned {} — fail-open (5xx may be transient)",
                    provider.getProviderId(), modelsPath, e.getStatusCode().value());
            return ProbeResult.ok(latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] {} {} failed: {}", provider.getProviderId(), modelsPath, e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    /**
     * Parse the provider's {@code generateKwargs} JSON into a map so a
     * {@code modelsPath} override is visible to {@link OpenAiModelsPath}. Returns
     * an empty map on null / blank / malformed JSON — a bad kwargs blob must not
     * knock a provider out of the pool; it simply falls back to the default path.
     */
    private Map<String, Object> parseKwargs(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("[Probe] ignoring unparseable generateKwargs: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
