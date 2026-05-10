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

    @Data
    public static class LanguageRequest {
        private String language;
    }
}
