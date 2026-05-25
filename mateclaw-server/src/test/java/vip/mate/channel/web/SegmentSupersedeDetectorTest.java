package vip.mate.channel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentSupersedeDetectorTest {

    @Test
    @DisplayName("marks pre-tool forged render success when replaced by real render success")
    void marksForgedRenderSuccess() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "DOCX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/7a8b9c0d-1e2f-3g4h-5i6j-7k8l9m0n1o2p"),
                tool("tc-0", "renderDocx", true),
                content("ct-1", "DOCX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/e4556d9f-cd69-4047-97c0-479dbbb6c256"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0))
                .containsEntry("superseded", true)
                .containsEntry("supersededBySegmentId", "ct-1")
                .containsEntry("supersededReason", "tool_result_replaced_model_claim");
    }

    @Test
    @DisplayName("does not mark legitimate preamble before a render tool")
    void leavesLegitimatePreambleAlone() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "我听懂了，需要生成 PDF。让我立即执行这个操作："),
                tool("tc-0", "renderPdf", true),
                content("ct-1", "PDF 已成功生成！\n\n下载链接: /api/v1/files/generated/e5fc9697-9d5a-4b20-a26d-89b623c8db9b"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0)).doesNotContainKey("superseded");
    }

    @Test
    @DisplayName("marks pre-tool forged write byte count when replaced by real write result")
    void marksForgedWriteSuccess() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "文件已成功写入！\n\n写入字节数：45 字节"),
                tool("tc-0", "write_file", true),
                content("ct-1", "文件已成功写入！\n\n写入字节数：43 字节"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0))
                .containsEntry("superseded", true)
                .containsEntry("supersededBySegmentId", "ct-1");
    }

    @Test
    @DisplayName("does not mark pre-tool success when the tool failed")
    void leavesFailedToolClaimVisible() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "PPTX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/c8e2f4a1-9b3d-4f8c-a5e7-d9f6b2c1a3e4"),
                tool("tc-0", "renderPptx", false),
                content("ct-1", "渲染失败：模板错误"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0)).doesNotContainKey("superseded");
    }

    @Test
    @DisplayName("v1 does not mark when the post-tool content is a general summary")
    void leavesSummaryFollowupAlone() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "文件内容已成功替换！\n\n替换次数：1 处"),
                tool("tc-0", "edit_file", true),
                content("ct-1", "所有文档生成和文件操作任务已完成。"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0)).doesNotContainKey("superseded");
    }

    @Test
    @DisplayName("does not cross another tool boundary looking for a replacement")
    void doesNotCrossToolBoundary() {
        List<Map<String, Object>> segments = segments(
                content("ct-0", "XLSX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/8c3d4a9f-2e1b-4f5a-b6c7-d8e9f0a1b2c3"),
                tool("tc-0", "renderXlsx", true),
                tool("tc-1", "renderDocx", true),
                content("ct-1", "XLSX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/f98d7fd0-3cda-4510-b056-5bd3c8343e19"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(0)).doesNotContainKey("superseded");
    }

    @Test
    @DisplayName("does not mark an actual post-tool result as a later pre-tool prediction")
    void doesNotMarkPostToolResult() {
        List<Map<String, Object>> segments = segments(
                tool("tc-0", "renderDocx", true),
                content("ct-0", "DOCX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/e4556d9f-cd69-4047-97c0-479dbbb6c256"),
                tool("tc-1", "renderDocx", true),
                content("ct-1", "DOCX 文件已成功生成！\n\n下载链接: /api/v1/files/generated/f98d7fd0-3cda-4510-b056-5bd3c8343e19"));

        SegmentSupersedeDetector.markSuperseded(segments);

        assertThat(segments.get(1)).doesNotContainKey("superseded");
    }

    @SafeVarargs
    private static List<Map<String, Object>> segments(Map<String, Object>... entries) {
        return new ArrayList<>(List.of(entries));
    }

    private static Map<String, Object> content(String id, String text) {
        Map<String, Object> segment = base(id, "content");
        segment.put("text", text);
        return segment;
    }

    private static Map<String, Object> tool(String id, String toolName, boolean success) {
        Map<String, Object> segment = base(id, "tool_call");
        segment.put("toolName", toolName);
        segment.put("toolSuccess", success);
        return segment;
    }

    private static Map<String, Object> base(String id, String type) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("type", type);
        segment.put("status", "completed");
        return segment;
    }
}
