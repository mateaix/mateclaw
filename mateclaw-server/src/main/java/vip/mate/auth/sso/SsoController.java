package vip.mate.auth.sso;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.sso.provider.SsoProvider;
import vip.mate.auth.sso.provider.SsoProviderRegistry;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SSO 单点登录 HTTP 端点。全部 permitAll (与 /auth/login 同级)。
 *
 * @author MateClaw Team
 */
@Tag(name = "SSO 单点登录")
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

    private final SsoProviderRegistry registry;
    private final SsoService ssoService;

    @Operation(summary = "列出已启用的 SSO Provider")
    @GetMapping("/providers")
    public R<List<Map<String, String>>> providers() {
        List<Map<String, String>> list = registry.listEnabled().stream()
                .map(p -> Map.of("id", p.id(), "displayName", p.displayName()))
                .collect(Collectors.toList());
        return R.ok(list);
    }

    @Operation(summary = "获取 SSO 授权 URL")
    @GetMapping("/{provider}/authorize")
    public R<Map<String, String>> authorize(@PathVariable String provider) {
        return R.ok(ssoService.handleAuthorize(provider));
    }

    @Operation(summary = "SSO 回调: 授权码换 JWT")
    @PostMapping("/{provider}/callback")
    public R<LoginResponse> callback(@PathVariable String provider,
                                      @RequestBody CallbackRequest body) {
        if (body == null || body.code() == null || body.state() == null) {
            return R.fail(400, "code 和 state 是必填项");
        }
        try {
            LoginResponse resp = ssoService.handleCallback(provider, body.code(), body.state());
            return R.ok(resp);
        } catch (SsoService.BindRequiredException e) {
            // 未绑定 (link-only 模式): 返回结构化引导信号 (非错误)
            return R.fail(200, Map.of(
                    "bindRequired", true,
                    "bindToken", e.getBindToken(),
                    "provider", e.getProvider(),
                    "displayName", e.getDisplayName() != null ? e.getDisplayName() : "").toString());
        }
    }

    @Operation(summary = "绑定 SSO 身份到已有账号 (link-only 模式)")
    @PostMapping("/bind")
    public R<LoginResponse> bind(@RequestBody BindRequest body) {
        if (body == null || body.bindToken() == null || body.username() == null || body.password() == null) {
            return R.fail(400, "bindToken, username, password 是必填项");
        }
        LoginResponse resp = ssoService.handleBind(body.bindToken(), body.username(), body.password());
        return R.ok(resp);
    }

    public record CallbackRequest(String code, String state) {}
    public record BindRequest(String bindToken, String username, String password) {}
}
