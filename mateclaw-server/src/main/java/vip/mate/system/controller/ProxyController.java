package vip.mate.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.system.proxy.ProxyManager;
import vip.mate.system.proxy.ProxySettings;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

/**
 * Global outbound-proxy configuration. System-wide, so reads require an admin
 * and writes require the global admin.
 */
@Tag(name = "网络代理")
@RestController
@RequestMapping("/api/v1/settings/proxy")
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyManager proxyManager;

    @Operation(summary = "获取全局代理配置")
    @GetMapping
    @RequireGlobalAdmin
    public R<ProxyConfigResponse> get() {
        return R.ok(toResponse(proxyManager.currentSettings()));
    }

    @Operation(summary = "保存全局代理配置")
    @PutMapping
    @RequireGlobalAdmin
    public R<ProxyConfigResponse> save(@RequestBody ProxyConfigRequest req) {
        ProxySettings saved = proxyManager.save(
                Boolean.TRUE.equals(req.getEnabled()),
                req.getUrl(),
                req.getNonProxyHosts());
        if (saved.enabled() && !saved.valid()) {
            return R.fail(saved.error());
        }
        return R.ok(toResponse(saved));
    }

    @Operation(summary = "测试代理连通性")
    @PostMapping("/test")
    @RequireGlobalAdmin
    public R<ProxyManager.ProbeResult> test(@RequestBody ProxyConfigRequest req) {
        ProxyManager.ProbeResult result = proxyManager.test(req.getUrl());
        return R.ok(result);
    }

    private ProxyConfigResponse toResponse(ProxySettings s) {
        ProxyConfigResponse resp = new ProxyConfigResponse();
        resp.setEnabled(s.enabled());
        resp.setUrl(s.url());
        resp.setNonProxyHosts(s.nonProxyHosts());
        resp.setValid(s.valid());
        resp.setError(s.error());
        return resp;
    }

    @Data
    public static class ProxyConfigRequest {
        private Boolean enabled;
        private String url;
        private String nonProxyHosts;
    }

    @Data
    public static class ProxyConfigResponse {
        private boolean enabled;
        private String url;
        private String nonProxyHosts;
        private boolean valid;
        private String error;
    }
}
