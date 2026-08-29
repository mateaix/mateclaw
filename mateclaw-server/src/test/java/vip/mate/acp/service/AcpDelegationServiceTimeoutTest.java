package vip.mate.acp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.acp.model.AcpEndpointEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcpDelegationServiceTimeoutTest {

    @Test
    @DisplayName("ACP prompt timeout defaults to 5 minutes when endpoint is unset")
    void defaultPromptTimeout() {
        AcpEndpointEntity endpoint = new AcpEndpointEntity();

        assertEquals(300_000L, AcpDelegationService.resolvePromptTimeoutMillis(endpoint));
    }

    @Test
    @DisplayName("ACP prompt timeout uses the endpoint setting")
    void endpointPromptTimeout() {
        AcpEndpointEntity endpoint = new AcpEndpointEntity();
        endpoint.setPromptTimeoutSeconds(900);

        assertEquals(900_000L, AcpDelegationService.resolvePromptTimeoutMillis(endpoint));
    }

    @Test
    @DisplayName("ACP prompt timeout is clamped to a one-hour hard ceiling")
    void clampPromptTimeout() {
        AcpEndpointEntity endpoint = new AcpEndpointEntity();
        endpoint.setPromptTimeoutSeconds(7200);

        assertEquals(3_600_000L, AcpDelegationService.resolvePromptTimeoutMillis(endpoint));
    }
}
