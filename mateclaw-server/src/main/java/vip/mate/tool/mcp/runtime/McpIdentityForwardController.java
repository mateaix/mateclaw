package vip.mate.tool.mcp.runtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves the JWKS (JSON Web Key Set) for the MCP identity-forward signing key,
 * so REST backends verifying on-behalf-of tokens can fetch the public key from
 * a well-known URL instead of out-of-band config (issue #459 follow-up).
 *
 * <p>The endpoint is intentionally unauthenticated — the public key is, by
 * definition, public. It is registered as {@code permitAll} in
 * {@code SecurityConfig}. Returns {@code {"keys":[]}} when token mode is
 * disabled or the key is not yet loaded.
 *
 * @author MateClaw Team
 */
@Tag(name = "MCP Identity Forward")
@RestController
@RequestMapping("/api/v1/mcp/.well-known")
@RequiredArgsConstructor
public class McpIdentityForwardController {

    private final McpIdentityForwardService identityForwardService;

    @Operation(summary = "JWKS — public keys for verifying MCP identity tokens")
    @GetMapping("/jwks.json")
    public Map<String, List<Map<String, Object>>> jwks() {
        return Map.of("keys", identityForwardService.publicKeyJwks());
    }
}
