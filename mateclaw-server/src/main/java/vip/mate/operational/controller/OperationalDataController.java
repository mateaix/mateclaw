package vip.mate.operational.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.operational.model.ExportTask;
import vip.mate.operational.service.OperationalDataExportService;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营数据导出 Controller — 仅全局管理员可访问。
 * <p>
 * 不暴露标准 REST API 端点，下载通过一次性 token 保护。
 */
@RestController
@RequestMapping("/api/v1/operational-data")
public class OperationalDataController {

    private final OperationalDataExportService exportService;

    public OperationalDataController(OperationalDataExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping("/generate")
    @RequireGlobalAdmin
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        try {
            ExportTask task = exportService.generate(startDate, endDate);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.getTaskId());
            result.put("status", task.getStatus());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 500);
            err.put("msg", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * 查询生成进度（驱动圆形进度条）
     */
    @GetMapping("/progress")
    @RequireGlobalAdmin
    public ResponseEntity<Map<String, Object>> progress(@RequestParam String taskId) {
        ExportTask task = exportService.getProgress(taskId);
        if (task == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 404);
            err.put("msg", "任务不存在或已过期");
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("step", task.getStep());
        result.put("total", task.getTotal());
        result.put("status", task.getStatus());
        if ("completed".equals(task.getStatus())) {
            result.put("downloadToken", task.getDownloadToken());
        }
        if ("failed".equals(task.getStatus())) {
            result.put("errorMessage", task.getErrorMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 下载已生成的文件（一次有效，需 downloadToken）
     */
    @GetMapping("/download")
    @RequireGlobalAdmin
    public ResponseEntity<Resource> download(
            @RequestParam String taskId,
            @RequestParam String token) {
        ExportTask task = exportService.confirmDownload(taskId, token);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.GONE)
                .contentType(MediaType.TEXT_PLAIN)
                .body(null);
        }

        try {
            if (!Files.exists(task.getFilePath())) {
                return ResponseEntity.status(HttpStatus.GONE).build();
            }

            // The one-time token was already atomically claimed in confirmDownload().
            InputStreamResource resource = new InputStreamResource(Files.newInputStream(task.getFilePath()));

            String encodedName = new String(task.getFileName().getBytes("UTF-8"), "ISO-8859-1");
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + encodedName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
