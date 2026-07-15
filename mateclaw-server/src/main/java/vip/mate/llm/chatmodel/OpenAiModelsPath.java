package vip.mate.llm.chatmodel;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single source of truth for the OpenAI-compatible <em>models-listing</em> path.
 *
 * <p>Both the discovery flow ({@code ModelDiscoveryService}) and the failover
 * liveness probe ({@code OpenAiCompatibleListModelsProbe}) list a provider's
 * models to do their jobs. Keeping the path resolution here means an operator's
 * {@code modelsPath} override is honored identically by both — otherwise a
 * self-hosted endpoint behind a non-standard prefix could be discoverable yet
 * still marked unhealthy by a probe hitting the wrong hard-coded path.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>an explicit {@code modelsPath} in {@code generateKwargs} — used verbatim
 *       (leading slash added if missing), for reverse-proxy / gateway prefixes
 *       such as {@code /openai/v1/models};</li>
 *   <li>otherwise {@code /v1/models}, collapsed to {@code /models} when the base
 *       URL already ends in a {@code /v{N}} segment (LM Studio {@code /v1},
 *       Zhipu {@code /v4}, Volcano Ark {@code /api/v3}) to avoid {@code /vN/v1/models}.</li>
 * </ol>
 * Mirrors the sibling {@code completionsPath} override on the chat path.
 */
public final class OpenAiModelsPath {

    /** Trailing {@code /v{N}} segment on a base URL (any numeric major version). */
    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*/v\\d+$");

    private OpenAiModelsPath() {}

    public static String resolve(String baseUrl, Map<String, Object> kwargs) {
        if (kwargs != null) {
            Object raw = kwargs.get("modelsPath");
            if (raw instanceof String value && StringUtils.hasText(value)) {
                String path = value.trim();
                return path.startsWith("/") ? path : "/" + path;
            }
        }
        String path = "/v1/models";
        if (baseUrl != null && VERSION_SUFFIX.matcher(baseUrl).matches()) {
            path = "/models";
        }
        return path;
    }
}
