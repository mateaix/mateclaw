package vip.mate.channel.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Marks model-predicted tool results that are replaced by the actual post-tool
 * answer segment.
 */
final class SegmentSupersedeDetector {

    static final String REASON_TOOL_RESULT_REPLACED_MODEL_CLAIM = "tool_result_replaced_model_claim";

    private static final Pattern GENERATED_FILE_URL =
            Pattern.compile("(?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+");
    private static final Pattern BYTE_COUNT =
            Pattern.compile("\\d+\\s*字节");
    private static final Pattern REPLACEMENT_COUNT =
            Pattern.compile("\\d+\\s*处");

    private SegmentSupersedeDetector() {
    }

    static void markSuperseded(List<Map<String, Object>> segments) {
        if (segments == null || segments.size() < 3) {
            return;
        }

        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> candidate = segments.get(i);
            if (!isContent(candidate) || Boolean.TRUE.equals(candidate.get("superseded"))
                    || followsToolResult(segments, i)) {
                continue;
            }

            Claim predictedClaim = parseClaim(String.valueOf(candidate.getOrDefault("text", "")));
            if (predictedClaim == null) {
                continue;
            }

            int toolIndex = nextToolIndexBeforeContent(segments, i + 1);
            if (toolIndex < 0) {
                continue;
            }
            Map<String, Object> tool = segments.get(toolIndex);
            if (Boolean.FALSE.equals(tool.get("toolSuccess"))
                    || !toolMatchesClaim(String.valueOf(tool.getOrDefault("toolName", "")), predictedClaim)) {
                continue;
            }

            int replacementIndex = nextMatchingContentIndex(segments, toolIndex + 1, predictedClaim);
            if (replacementIndex < 0) {
                continue;
            }

            Map<String, Object> replacement = segments.get(replacementIndex);
            candidate.put("superseded", true);
            candidate.put("supersededBySegmentId", String.valueOf(replacement.getOrDefault("id", "")));
            candidate.put("supersededReason", REASON_TOOL_RESULT_REPLACED_MODEL_CLAIM);
        }
    }

    private static int nextToolIndexBeforeContent(List<Map<String, Object>> segments, int start) {
        for (int i = start; i < segments.size(); i++) {
            Map<String, Object> segment = segments.get(i);
            if (isToolCall(segment)) {
                return i;
            }
            if (isContent(segment)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean followsToolResult(List<Map<String, Object>> segments, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Map<String, Object> segment = segments.get(i);
            if (isContent(segment)) {
                return false;
            }
            if (isToolCall(segment)) {
                return true;
            }
        }
        return false;
    }

    private static int nextMatchingContentIndex(List<Map<String, Object>> segments, int start, Claim predictedClaim) {
        for (int i = start; i < segments.size(); i++) {
            Map<String, Object> segment = segments.get(i);
            if (isToolCall(segment)) {
                return -1;
            }
            if (!isContent(segment)) {
                continue;
            }
            Claim actualClaim = parseClaim(String.valueOf(segment.getOrDefault("text", "")));
            if (predictedClaim.sameKind(actualClaim)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isContent(Map<String, Object> segment) {
        return segment != null && "content".equals(segment.get("type"));
    }

    private static boolean isToolCall(Map<String, Object> segment) {
        return segment != null && "tool_call".equals(segment.get("type"));
    }

    private static Claim parseClaim(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if ((upper.contains("成功生成") || text.contains("已生成")) && GENERATED_FILE_URL.matcher(text).find()) {
            for (String format : List.of("PDF", "DOCX", "PPTX", "XLSX")) {
                if (upper.contains(format)) {
                    return new Claim("render", format);
                }
            }
        }
        if (text.contains("成功写入") && BYTE_COUNT.matcher(text).find()) {
            return new Claim("write", "");
        }
        if (text.contains("成功替换") && REPLACEMENT_COUNT.matcher(text).find()) {
            return new Claim("edit", "");
        }
        return null;
    }

    private static boolean toolMatchesClaim(String toolName, Claim claim) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        return switch (claim.type) {
            case "render" -> normalized.contains("render" + claim.detail.toLowerCase(Locale.ROOT));
            case "write" -> "write_file".equals(normalized);
            case "edit" -> "edit_file".equals(normalized);
            default -> false;
        };
    }

    private record Claim(String type, String detail) {
        boolean sameKind(Claim other) {
            return other != null && type.equals(other.type) && detail.equals(other.detail);
        }
    }
}
