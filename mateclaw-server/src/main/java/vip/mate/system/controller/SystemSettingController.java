package vip.mate.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;

@Tag(name = "系统设置")
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SystemSettingController {

    private final SystemSettingService systemSettingService;

    @Operation(summary = "获取系统设置")
    @GetMapping
    public R<SystemSettingsDTO> getSettings() {
        return R.ok(systemSettingService.getSettings());
    }

    @Operation(summary = "保存系统设置")
    @PutMapping
    public R<SystemSettingsDTO> saveSettings(@RequestBody SystemSettingsDTO dto) {
        return R.ok(systemSettingService.saveSettings(dto));
    }

    @Operation(summary = "获取当前语言")
    @GetMapping("/language")
    public R<String> getLanguage() {
        return R.ok(systemSettingService.getLanguage());
    }

    @Operation(summary = "更新当前语言")
    @PutMapping("/language")
    public R<String> saveLanguage(@RequestBody LanguageRequest request) {
        return R.ok(systemSettingService.saveLanguage(request.getLanguage()));
    }

    /**
     * Dedicated endpoint for the multimodal sidecar configuration.
     * <p>
     * Separated from the bulk {@code PUT /settings} because the bulk endpoint
     * now guards sidecar keys with null checks (so unrelated settings pages
     * can't clobber them via partial payloads). This endpoint always writes
     * both fields, so passing {@code null} for either explicitly clears that
     * sidecar — preserving the "clear via UI" UX without leaking the
     * write-on-null semantics into every other settings save.
     */
    @Operation(summary = "更新多模态 sidecar 配置")
    @PutMapping("/sidecar")
    public R<SystemSettingsDTO> saveSidecar(@RequestBody SidecarRequest request) {
        return R.ok(systemSettingService.updateSidecarSettings(
                request.getDefaultVisionModelId(),
                request.getDefaultVideoModelId()));
    }

    @Data
    public static class LanguageRequest {
        private String language;
    }

    /**
     * Body for {@code PUT /settings/sidecar}. Both fields are nullable;
     * {@code null} means "explicit clear". Field absence in the JSON
     * payload also deserializes to null, which is the same outcome — the
     * sidecar UI is the only caller of this endpoint and always sends both
     * fields, so the absent-vs-null distinction doesn't matter here.
     */
    @Data
    public static class SidecarRequest {
        private Long defaultVisionModelId;
        private Long defaultVideoModelId;
    }
}
