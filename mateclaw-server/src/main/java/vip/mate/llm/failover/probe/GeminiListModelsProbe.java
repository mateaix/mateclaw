package vip.mate.llm.failover.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import vip.mate.llm.failover.ProbeResult;
import vip.mate.llm.failover.ProviderProbeStrategy;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Probes a native Gemini provider by listing its models via
 * {@code GET /v1beta/models?key=...}.
 *
 * <p>Auth failures (400 with an API-key error, 401, 403) are definitive
 * negatives → HARD remove. Other 4xx/5xx are treated fail-open, since the
 * chat path is the authoritative check.</p>
 */
@Slf4j
@Component
public class GeminiListModelsProbe implements ProviderProbeStrategy {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.GEMINI_NATIVE;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        if (provider == null || !StringUtils.hasText(provider.getApiKey())) {
            return ProbeResult.fail(0, "API key not configured");
        }
        String baseUrl = provider.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        baseUrl = stripTrailingSlash(baseUrl.trim());
        long start = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();
            client.get()
                    .uri("/v1beta/models?key={key}", provider.getApiKey().trim())
                    .retrieve()
                    .body(String.class);
            return ProbeResult.ok(System.currentTimeMillis() - start);
        } catch (HttpClientErrorException e) {
            long latency = System.currentTimeMillis() - start;
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403
                    || (status == 400 && e.getResponseBodyAsString().contains("API key"))) {
                log.debug("[Probe] {} gemini models auth failed: {}", provider.getProviderId(), status);
                return ProbeResult.fail(latency, "auth failed (" + status + ")");
            }
            log.info("[Probe] {} gemini models returned {} — fail-open (inconclusive)",
                    provider.getProviderId(), status);
            return ProbeResult.ok(latency);
        } catch (HttpServerErrorException e) {
            long latency = System.currentTimeMillis() - start;
            log.info("[Probe] {} gemini models returned {} — fail-open (5xx may be transient)",
                    provider.getProviderId(), e.getStatusCode().value());
            return ProbeResult.ok(latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] {} gemini models failed: {}", provider.getProviderId(), e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
