package vip.mate.tool.mcp.runtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 * <p>The response carries {@code Cache-Control: public, max-age=300} (5 min) so
 * backends that poll on every token verification don't hammer the server. When
 * an operator rotates the signing key, they <b>must</b> also bump
 * {@code key-id} — the {@code kid} in the JWKS is the cache-busting signal, and
 * a stale cache keyed on the old {@code kid} would serve a mismatched public
 * key.
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
    public ResponseEntity<Map<String, List<Map<String, Object>>>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(Map.of("keys", identityForwardService.publicKeyJwks()));
    }
}
