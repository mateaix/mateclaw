package vip.mate.llm.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.llm.oauth.OpenAIDeviceCodeService;
import vip.mate.llm.oauth.OpenAIDeviceCodeService.DeviceCodePollResult;
import vip.mate.llm.oauth.OpenAIDeviceCodeService.DeviceCodeStartResult;
import vip.mate.llm.oauth.OpenAIOAuthService;
import vip.mate.llm.oauth.OpenAIOAuthService.OAuthAuthorizeResult;
import vip.mate.llm.oauth.OpenAIOAuthService.OAuthStatusResult;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

@Tag(name = "OpenAI OAuth")
@RestController
@RequestMapping("/api/v1/oauth/openai")
@RequiredArgsConstructor
public class OAuthController {

    private final OpenAIOAuthService oauthService;
    private final OpenAIDeviceCodeService deviceCodeService;

    @Operation(summary = "获取 OAuth 授权 URL（自动选 LOCAL / MANUAL_PASTE 模式）")
    @GetMapping("/authorize")
    @RequireGlobalAdmin
    public R<OAuthAuthorizeResult> authorize(HttpServletRequest request) {
        // 用 Host header 判断是否远程部署 — 远程则不启 localhost server，进 MANUAL_PASTE 流。
        // 优先 X-Forwarded-Host（反向代理后的实际入口），fallback 到 Host。
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        return R.ok(oauthService.buildAuthorizeUrl(host));
    }

    /**
     * MANUAL_PASTE 模式：用户复制浏览器地址栏中的回调 URL（含 ?code=...&state=...）
     * 粘贴给 MateClaw 完成 token 交换。Server 部署在 Linux / 远程 host 时唯一可用路径，
     * 因为 OpenAI Codex CLI 的 client_id 强制 redirect_uri = http://localhost:1455/...
     * 远程浏览器无法到达 server 的 localhost，本路由替代 callback server。
     */
    @Operation(summary = "MANUAL_PASTE 模式：用户粘贴浏览器回调 URL 完成 OAuth")
    @PostMapping("/callback-paste")
    @RequireGlobalAdmin
    public R<Void> callbackPaste(@RequestBody PasteRequest request) {
        oauthService.completeFromPastedUrl(request.callbackUrl());
        return R.ok();
    }

    /** Request body for {@link #callbackPaste(PasteRequest)}. */
    public record PasteRequest(String callbackUrl) {}

    @Operation(summary = "Device flow: start — request user_code")
    @PostMapping("/device/start")
    @RequireGlobalAdmin
    public R<DeviceCodeStartResult> deviceStart() {
        return R.ok(deviceCodeService.start());
    }

    @Operation(summary = "Device flow: poll for completion")
    @PostMapping("/device/poll")
    @RequireGlobalAdmin
    public R<DeviceCodePollResult> devicePoll(@RequestBody DeviceRequest request) {
        return R.ok(deviceCodeService.poll(request.deviceAuthId()));
    }

    @Operation(summary = "Device flow: cancel a pending session")
    @PostMapping("/device/cancel")
    @RequireGlobalAdmin
    public R<Void> deviceCancel(@RequestBody DeviceRequest request) {
        deviceCodeService.cancel(request.deviceAuthId());
        return R.ok();
    }

    /** Request body for {@link #devicePoll} / {@link #deviceCancel}. */
    public record DeviceRequest(String deviceAuthId) {}

    @Operation(summary = "手动刷新 Token")
    @PostMapping("/refresh")
    @RequireGlobalAdmin
    public R<Void> refresh() {
        oauthService.refreshToken();
        return R.ok();
    }

    @Operation(summary = "清除 OAuth 凭证")
    @DeleteMapping("/revoke")
    @RequireGlobalAdmin
    public R<Void> revoke() {
        oauthService.revokeToken();
        return R.ok();
    }

    @Operation(summary = "获取 OAuth 连接状态")
    @GetMapping("/status")
    @RequireGlobalAdmin
    public R<OAuthStatusResult> status() {
        return R.ok(oauthService.getStatus());
    }
}
