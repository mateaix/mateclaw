package vip.mate.llm.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.oauth.OpenAIDeviceCodeService.DeviceCodePollResult;
import vip.mate.llm.oauth.OpenAIDeviceCodeService.DeviceCodeStartResult;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the device authorization grant flow.
 *
 * <p>{@link OpenAIDeviceCodeService} is exercised through a mocked OpenAI endpoint
 * (via {@link MockRestServiceServer}). The token exchange path
 * ({@code OpenAIOAuthService#exchangeTokenWithVerifier}) is mocked so we never
 * touch the database — we only verify it is invoked with the correct args.
 */
class OpenAIDeviceCodeServiceTest {

    private OpenAIOAuthService oauthService;
    private OpenAIDeviceCodeService deviceCodeService;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() throws Exception {
        oauthService = mock(OpenAIOAuthService.class);
        deviceCodeService = new OpenAIDeviceCodeService(oauthService, new ObjectMapper());

        // Tighten config knobs so tests don't sleep
        setField(deviceCodeService, "pollMinIntervalMs", 0L);
        setField(deviceCodeService, "defaultSessionTtlSeconds", 900L);
        setField(deviceCodeService, "userAgent", "test-agent/0.0");

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        deviceCodeService.setRestClient(builder.build());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = OpenAIDeviceCodeService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---------------------------------------------------------------------
    // start()
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("start sends JSON body with client_id and parses all response fields")
    void start_parsesAllFields() {
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_USERCODE_URL))
                .andExpect(header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.client_id").value(OpenAIDeviceCodeService.CLIENT_ID))
                .andRespond(withSuccess(
                        "{\"device_auth_id\":\"dev-abc-123\","
                                + "\"user_code\":\"WXYZ-1234\","
                                + "\"interval\":7,"
                                + "\"expires_in\":600,"
                                + "\"verification_uri\":\"https://auth.openai.com/codex/device\","
                                + "\"verification_uri_complete\":\"https://auth.openai.com/codex/device?user_code=WXYZ-1234\"}",
                        MediaType.APPLICATION_JSON));

        DeviceCodeStartResult result = deviceCodeService.start();

        assertEquals("dev-abc-123", result.deviceAuthId());
        assertEquals("WXYZ-1234", result.userCode());
        assertEquals(7, result.intervalSeconds());
        assertEquals(600, result.expiresInSeconds());
        assertEquals("https://auth.openai.com/codex/device", result.verificationUrl());
        assertEquals("https://auth.openai.com/codex/device?user_code=WXYZ-1234",
                result.verificationUrlComplete());
        assertEquals(1, deviceCodeService.activeSessionCount());

        mockServer.verify();
    }

    @Test
    @DisplayName("start defaults verification URL when not returned by OpenAI")
    void start_defaultsVerificationUrl() {
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_USERCODE_URL))
                .andRespond(withSuccess(
                        "{\"device_auth_id\":\"d1\",\"user_code\":\"AB-CD\","
                                + "\"interval\":5,\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));

        DeviceCodeStartResult result = deviceCodeService.start();
        assertEquals(OpenAIDeviceCodeService.DEFAULT_VERIFICATION_URL, result.verificationUrl());
    }

    @Test
    @DisplayName("start throws MateClawException on transport failure")
    void start_propagatesTransportFailures() {
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_USERCODE_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> deviceCodeService.start());
        assertEquals("err.llm.device_code_start_failed", ex.getMsgKey());
    }

    @Test
    @DisplayName("start throws when response is missing required fields")
    void start_rejectsIncompleteResponse() {
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_USERCODE_URL))
                .andRespond(withSuccess("{\"interval\":5}", MediaType.APPLICATION_JSON));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> deviceCodeService.start());
        assertEquals("err.llm.device_code_start_failed", ex.getMsgKey());
    }

    // ---------------------------------------------------------------------
    // poll()
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("poll returns EXPIRED for unknown session")
    void poll_unknownSessionExpired() {
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("not-a-real-session").status());
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll(null).status());
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("").status());
    }

    @Test
    @DisplayName("poll sends JSON body and returns PENDING for HTTP 403 (user has not finished yet)")
    void poll_403MapsToPending() {
        expectStart("dev-1", "USER-1");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.device_auth_id").value("dev-1"))
                .andExpect(jsonPath("$.user_code").value("USER-1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.PENDING,
                deviceCodeService.poll("dev-1").status());
        verifyNoInteractions(oauthService);
    }

    @Test
    @DisplayName("poll returns PENDING for HTTP 404 (per OpenAI deviceauth contract)")
    void poll_404MapsToPending() {
        expectStart("dev-1b", "USER-1B");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.PENDING,
                deviceCodeService.poll("dev-1b").status());
    }

    @Test
    @DisplayName("poll still maps RFC 8628 400+authorization_pending to PENDING for forward-compat")
    void poll_rfcAuthorizationPendingMapsToPending() {
        expectStart("dev-1c", "USER-1C");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"authorization_pending\"}"));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.PENDING,
                deviceCodeService.poll("dev-1c").status());
        verifyNoInteractions(oauthService);
    }

    @Test
    @DisplayName("poll returns PENDING when OpenAI replies 400 slow_down")
    void poll_slowDownMapsToPending() {
        expectStart("dev-2", "USER-2");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"slow_down\"}"));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.PENDING,
                deviceCodeService.poll("dev-2").status());
    }

    @Test
    @DisplayName("poll returns EXPIRED + drops session when OpenAI replies 400 expired_token")
    void poll_expiredTokenDropsSession() {
        expectStart("dev-3", "USER-3");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"expired_token\"}"));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("dev-3").status());
        // session was removed — next poll returns EXPIRED without hitting the network
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("dev-3").status());
    }

    @Test
    @DisplayName("poll returns EXPIRED when user denies access")
    void poll_accessDeniedDropsSession() {
        expectStart("dev-4", "USER-4");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"access_denied\"}"));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("dev-4").status());
    }

    @Test
    @DisplayName("poll returns COMPLETED + invokes token exchange when authorization_code arrives")
    void poll_completedExchangesToken() {
        expectStart("dev-5", "USER-5");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"authorization_code\":\"auth-code-xyz\","
                                + "\"code_verifier\":\"verifier-xyz\"}",
                        MediaType.APPLICATION_JSON));

        deviceCodeService.start();
        DeviceCodePollResult result = deviceCodeService.poll("dev-5");

        assertEquals(DeviceCodePollResult.Status.COMPLETED, result.status());
        verify(oauthService).exchangeTokenWithVerifier(
                eq("auth-code-xyz"),
                eq("verifier-xyz"),
                eq(OpenAIDeviceCodeService.DEVICE_REDIRECT_URI));
        assertEquals(0, deviceCodeService.activeSessionCount());
    }

    @Test
    @DisplayName("poll keeps session and returns PENDING when 200 body has no authorization_code")
    void poll_inlinePendingErrorMapsToPending() {
        expectStart("dev-6", "USER-6");
        // Some flavours of the endpoint reply 200 with {error: authorization_pending}
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"error\":\"authorization_pending\"}",
                        MediaType.APPLICATION_JSON));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.PENDING,
                deviceCodeService.poll("dev-6").status());
        assertEquals(1, deviceCodeService.activeSessionCount());
    }

    @Test
    @DisplayName("poll returns EXPIRED when authorization_code present but code_verifier missing")
    void poll_missingCodeVerifierDropsSession() {
        expectStart("dev-7", "USER-7");
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"authorization_code\":\"only-code\"}",
                        MediaType.APPLICATION_JSON));

        deviceCodeService.start();
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("dev-7").status());
        verifyNoInteractions(oauthService);
    }

    // ---------------------------------------------------------------------
    // cancel()
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("cancel removes the session so subsequent poll returns EXPIRED")
    void cancel_dropsSession() {
        expectStart("dev-cancel", "USER-CANCEL");

        deviceCodeService.start();
        assertEquals(1, deviceCodeService.activeSessionCount());

        deviceCodeService.cancel("dev-cancel");
        assertEquals(0, deviceCodeService.activeSessionCount());
        assertEquals(DeviceCodePollResult.Status.EXPIRED,
                deviceCodeService.poll("dev-cancel").status());
    }

    @Test
    @DisplayName("cancel handles null/missing IDs without throwing")
    void cancel_nullSafe() {
        assertDoesNotThrow(() -> deviceCodeService.cancel(null));
        assertDoesNotThrow(() -> deviceCodeService.cancel("never-existed"));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Register the usercode-endpoint expectation; caller must invoke start() afterwards. */
    private void expectStart(String deviceAuthId, String userCode) {
        mockServer.expect(requestTo(OpenAIDeviceCodeService.DEVICE_USERCODE_URL))
                .andRespond(withSuccess(
                        "{\"device_auth_id\":\"" + deviceAuthId + "\","
                                + "\"user_code\":\"" + userCode + "\","
                                + "\"interval\":5,\"expires_in\":900}",
                        MediaType.APPLICATION_JSON));
    }
}
