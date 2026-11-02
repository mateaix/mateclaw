package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.Buffer;

import vip.mate.llm.model.ModelProviderEntity;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Final request policies shared by blocking and asynchronous SDK calls. */
@Slf4j
final class OpenAiCompatibleHttpInterceptor implements Interceptor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelProviderEntity provider;
    private final String endpoint;
    private final Map<String, String> headers;
    private final boolean search;
    private final Duration timeout;

    OpenAiCompatibleHttpInterceptor(
            ModelProviderEntity provider,
            String endpoint,
            Map<String, String> headers,
            boolean search,
            Duration timeout) {
        this.provider = provider;
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers);
        this.search = search;
        this.timeout = timeout;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Buffer buffer = new Buffer();
        original.body().writeTo(buffer);
        ObjectNode request = (ObjectNode) JSON.readTree(buffer.readByteArray());
        ObjectNode rewritten = OpenAiRequestRewriter.rewrite(request, provider, search);
        log.debug(
                "OpenAI-compatible request: provider={}, model={}, messages={}, tools={},"
                        + " stream={}",
                provider.getProviderId(),
                rewritten.path("model").asText(),
                rewritten.path("messages").size(),
                rewritten.path("tools").size(),
                rewritten.path("stream").asBoolean());
        // Only Azure's unified v1 surface rejects the legacy SDK api-version.
        // Keep explicit endpoint query values authoritative and avoid duplicating
        // parameters already present there when copying SDK query parameters.
        HttpUrl parsedEndpoint = HttpUrl.get(endpoint);
        String host = parsedEndpoint.host();
        boolean azureUnifiedEndpoint =
                (host.endsWith(".openai.azure.com") || host.endsWith(".cognitiveservices.azure.com"))
                        && parsedEndpoint.encodedPath().startsWith("/openai/v1/");
        HttpUrl.Builder url = parsedEndpoint.newBuilder();
        if (azureUnifiedEndpoint) {
            for (String name : parsedEndpoint.queryParameterNames()) {
                if ("api-version".equalsIgnoreCase(name)) url.removeAllQueryParameters(name);
            }
        }
        for (String name : original.url().queryParameterNames()) {
            if (azureUnifiedEndpoint && "api-version".equalsIgnoreCase(name)) continue;
            if (parsedEndpoint.queryParameterNames().contains(name)) continue;
            for (String value : original.url().queryParameterValues(name)) {
                url.addQueryParameter(name, value);
            }
        }
        Request.Builder updated =
                original.newBuilder()
                        .url(url.build())
                        .method(
                                original.method(),
                                RequestBody.create(
                                        JSON.writeValueAsBytes(rewritten),
                                        original.body().contentType()));
        headers.forEach(updated::header);
        Response response =
                chain.withConnectTimeout(
                                (int) HttpTimeouts.CONNECT_TIMEOUT.toMillis(),
                                TimeUnit.MILLISECONDS)
                        .withReadTimeout((int) timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .proceed(updated.build());
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null || body.contentType() == null) return response;
        if ("text".equals(body.contentType().type())
                && "event-stream".equals(body.contentType().subtype()))
            return response.newBuilder()
                    .removeHeader("Content-Length")
                    .body(new OpenAiReasoningResponseBody(body))
                    .build();
        if ("application".equals(body.contentType().type())
                && "json".equals(body.contentType().subtype())) {
            String normalized = OpenAiReasoningResponseNormalizer.normalize(body.string());
            ResponseBody replacement = ResponseBody.create(normalized, body.contentType());
            return response.newBuilder()
                    .header("Content-Length", String.valueOf(replacement.contentLength()))
                    .body(replacement)
                    .build();
        }
        return response;
    }
}
