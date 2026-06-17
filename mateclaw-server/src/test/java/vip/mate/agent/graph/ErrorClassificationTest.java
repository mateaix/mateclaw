package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RFC-009 P3.2: classification tests for the new error types
 * ({@link NodeStreamingChatHelper.ErrorType#BILLING},
 * {@link NodeStreamingChatHelper.ErrorType#MODEL_NOT_FOUND}).
 *
 * <p>These two are split out from {@code AUTH_ERROR} / {@code CLIENT_ERROR}
 * because the right action is to switch provider, not to terminate.
 * Mis-classifying a billing error as auth would break the whole call chain.</p>
 */
class ErrorClassificationTest {

    private static NodeStreamingChatHelper.ErrorType classify(Throwable t) throws Exception {
        Method m = NodeStreamingChatHelper.class.getDeclaredMethod("classifyError", Throwable.class);
        m.setAccessible(true);
        return (NodeStreamingChatHelper.ErrorType) m.invoke(null, t);
    }

    // ===== BILLING =====

    @Test
    @DisplayName("HTTP 402 → BILLING")
    void status402IsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("402 Payment Required")));
    }

    @Test
    @DisplayName("OpenAI 'insufficient_quota' → BILLING")
    void openaiQuotaIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("Error code: insufficient_quota — please check your plan")));
    }

    @Test
    @DisplayName("Anthropic 'credit balance is too low' → BILLING")
    void anthropicCreditIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("Your credit balance is too low to access the API")));
    }

    @Test
    @DisplayName("'You exceeded your current quota' → BILLING")
    void quotaExceededIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("You exceeded your current quota, please check your plan")));
    }

    // ===== MODEL_NOT_FOUND =====

    @Test
    @DisplayName("'Model not exist' → MODEL_NOT_FOUND")
    void modelNotExistIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("Model not exist: gpt-99")));
    }

    @Test
    @DisplayName("'model_not_found' → MODEL_NOT_FOUND")
    void modelNotFoundCodeIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("Error: model_not_found")));
    }

    @Test
    @DisplayName("DashScope '[InvalidParameter] url error' → MODEL_NOT_FOUND (not CLIENT_ERROR)")
    void dashscopeInvalidParameterIsModelNotFound() throws Exception {
        // Despite the wording, DashScope returns this when the model id is unknown
        // — the right action is to try a fallback provider, not terminate as 400.
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("[InvalidParameter] url error, please check url")));
    }

    @Test
    @DisplayName("Anthropic 'model does not exist' → MODEL_NOT_FOUND")
    void anthropicDoesNotExistIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("model claude-99 does not exist")));
    }

    // ===== Regression: existing classifications still work =====

    @Test
    @DisplayName("HTTP 401 still classifies as AUTH_ERROR (not billing)")
    void status401StillAuth() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new RuntimeException("401 Unauthorized: Invalid API Key")));
    }

    @Test
    @DisplayName("HTTP 429 still classifies as RATE_LIMIT")
    void status429StillRateLimit() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.RATE_LIMIT,
                classify(new RuntimeException("429 Too Many Requests")));
    }

    @Test
    @DisplayName("Plain 400 Bad Request still classifies as CLIENT_ERROR")
    void status400StillClientError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.CLIENT_ERROR,
                classify(new RuntimeException("400 Bad Request: malformed JSON")));
    }

    // ===== Transient TLS / IO errors → SERVER_ERROR (retryable) =====
    //
    // Without these, a single TLS handshake hiccup or socket reset mid-stream
    // surfaces to the user as "LLM 调用失败" with zero retries — the existing
    // exponential-backoff loop only triggers on RATE_LIMIT / SERVER_ERROR.
    // Routing them through SERVER_ERROR gives them ~3s/6s/12s retry budget,
    // which is enough to absorb transient network glitches without user impact.

    @Test
    @DisplayName("SSL bad_record_mac (RFC 5246 fatal alert 20) → SERVER_ERROR")
    void sslBadRecordMacIsServerError() throws Exception {
        // Real-world chain: WebClientRequestException → SSLException("Received
        // fatal alert: bad_record_mac"). The leaf message contains
        // bad_record_mac, the wrapper contributes SSLException class name.
        javax.net.ssl.SSLException sslEx = new javax.net.ssl.SSLException(
                "Received fatal alert: bad_record_mac");
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new RuntimeException("(bad_record_mac) Received fatal alert", sslEx)));
    }

    @Test
    @DisplayName("plain SSLException class in chain → SERVER_ERROR")
    void sslExceptionClassIsServerError() throws Exception {
        // extractFullErrorChain appends getClass().getSimpleName(), so even
        // an SSLException without a recognizable message text gets matched
        // via the class name token.
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new javax.net.ssl.SSLException("handshake aborted")));
    }

    @Test
    @DisplayName("SSLHandshakeException → SERVER_ERROR")
    void sslHandshakeExceptionIsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new javax.net.ssl.SSLHandshakeException("Remote host closed connection during handshake")));
    }

    @Test
    @DisplayName("SocketException (peer reset mid-stream) → SERVER_ERROR")
    void socketExceptionIsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new java.net.SocketException("Connection reset by peer")));
    }

    @Test
    @DisplayName("Reactor Netty 'Connection prematurely closed' → SERVER_ERROR")
    void prematureCloseIsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new RuntimeException("Connection prematurely closed BEFORE response")));
    }

    @Test
    @DisplayName("Broken pipe (server cut TCP write half) → SERVER_ERROR")
    void brokenPipeIsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new java.io.IOException("Broken pipe")));
    }

    @Test
    @DisplayName("WebClientRequestException with SSL cause → SERVER_ERROR (not UNKNOWN)")
    void webClientRequestSslIsServerError() throws Exception {
        // The exact production failure pattern: Reactor wraps the SSL leaf in
        // WebClientRequestException. The chain walker sees both the wrapper
        // class name AND the leaf SSLException class name, and the message
        // string carries bad_record_mac.
        Throwable cause = new javax.net.ssl.SSLException("Received fatal alert: bad_record_mac");
        Throwable wrapped = new RuntimeException(
                "WebClientRequestException: bad_record_mac; nested exception", cause);
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR, classify(wrapped));
    }

    @Test
    @DisplayName("AUTH still wins over TLS chain (real auth failure not masked)")
    void authStillWinsOverTlsChain() throws Exception {
        // A 401 response wrapped by Reactor still carries WebClientResponseException
        // in the chain — the classifier must not see "WebClient*Exception" and
        // demote it to SERVER_ERROR. AUTH_ERROR is checked before SERVER_ERROR
        // in classifyError(), so 401 keywords win.
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new RuntimeException("401 Unauthorized: Invalid API Key (WebClientResponseException)")));
    }

    // ===== AI-gateway resilience: 5xx classified BEFORE 4xx =====
    //
    // Reverse proxies / AI gateways often surface an upstream 5xx as an HTTP 400
    // whose body still describes the outage. SERVER_ERROR must be matched before
    // CLIENT_ERROR so the transient root cause wins and the call is retried,
    // instead of being terminated as a non-retryable client error.

    @Test
    @DisplayName("502 whose chain also carries 'Bad Request' → SERVER_ERROR (5xx wins)")
    void gateway502WithBadRequestWinsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new RuntimeException("502 Bad Gateway: Bad Request from upstream proxy")));
    }

    @Test
    @DisplayName("503 mixed with 'invalid_request_error' → SERVER_ERROR")
    void mixed503And400IsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new RuntimeException("503 Service Unavailable (invalid_request_error in body)")));
    }

    @Test
    @DisplayName("Gateway-rewritten 400 'model is overloaded' → SERVER_ERROR")
    void gatewayOverloadedIsServerError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,
                classify(new RuntimeException("400 Bad Request: model is overloaded, please try again")));
    }

    // ===== Provider billing patterns (Chinese + numeric codes) → BILLING =====

    @Test
    @DisplayName("Chinese '余额不足 / 请充值' → BILLING")
    void chineseInsufficientBalanceIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("调用失败：账户余额不足，请充值后重试")));
    }

    @Test
    @DisplayName("Zhipu '\"code\":\"1113\"' → BILLING")
    void zhipuCode1113IsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("{\"error\":{\"code\":\"1113\",\"message\":\"insufficient balance\"}}")));
    }

    @Test
    @DisplayName("'AccountBalanceNotEnough' → BILLING")
    void accountBalanceNotEnoughIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("AccountBalanceNotEnough: balance not enough")));
    }

    // ===== Infrastructure-fatal errors → AUTH_ERROR (HARD, no same-model retry) =====
    //
    // DNS / TLS-trust failures do not self-heal on retry. They are routed through
    // AUTH_ERROR so the loop breaks straight to the fallback chain instead of
    // burning the SERVER_ERROR retry budget on an unrecoverable condition.

    @Test
    @DisplayName("UnknownHostException (DNS) → AUTH_ERROR")
    void unknownHostIsAuthError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new java.net.UnknownHostException("api.example.com")));
    }

    @Test
    @DisplayName("CertificateException → AUTH_ERROR")
    void certificateExceptionIsAuthError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new java.security.cert.CertificateException("certificate expired")));
    }

    @Test
    @DisplayName("SSLPeerUnverifiedException → AUTH_ERROR")
    void sslPeerUnverifiedIsAuthError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new javax.net.ssl.SSLPeerUnverifiedException("peer not authenticated")));
    }

    @Test
    @DisplayName("OpenSSL-style 'certificate verify failed' → AUTH_ERROR")
    void certificateVerifyFailedIsAuthError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new RuntimeException("SSL error: certificate verify failed (self-signed certificate in chain)")));
    }

    @Test
    @DisplayName("Java TLS 'PKIX path building failed' (uppercase PKIX) → AUTH_ERROR")
    void pkixPathBuildingIsAuthError() throws Exception {
        // The real Java message capitalizes PKIX. Since the error chain is not
        // lower-cased, a lowercase pattern would never match and the fatal cert
        // failure would be retried as SERVER_ERROR. Guard against that regression.
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new RuntimeException(
                        "PKIX path building failed: unable to find valid certification path to requested target")));
    }
}
