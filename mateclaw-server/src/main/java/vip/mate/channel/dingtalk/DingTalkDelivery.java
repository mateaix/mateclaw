package vip.mate.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** DingTalk's safe Markdown budget, including repeated structural markers. */
final class DingTalkDelivery {
    private static final int LIMIT = 3000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private DingTalkDelivery() {}

    static List<String> chunks(String text) {
        if (text.length() <= 3500) return List.of(text);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String fence = "";
        String tableHeader = "";
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            boolean marker = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (fence.isEmpty() && i + 1 < lines.length && line.contains("|")
                    && lines[i + 1].trim().matches("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")) {
                tableHeader = line + "\n" + lines[++i] + "\n";
                line = tableHeader;
            } else {
                if (fence.isEmpty() && !line.contains("|")) tableHeader = "";
                line += i < lines.length - 1 ? "\n" : "";
            }
            String remaining = line;
            while (!remaining.isEmpty()) {
                int reserve = !fence.isEmpty() ? fenceMarker(fence).length() + 1
                        : marker ? fenceMarker(trimmed).length() + 1 : 0;
                int available = LIMIT - reserve - current.length();
                if (remaining.length() <= available) {
                    current.append(remaining);
                    remaining = "";
                } else {
                    // Prefer whole lines/rows; hard split only oversized lines.
                    String prefix = !fence.isEmpty() ? fence + "\n" : tableHeader;
                    if (current.length() > prefix.length()) {
                        result.add(current + (fence.isEmpty() ? "" : "\n" + fenceMarker(fence)));
                        current = new StringBuilder(prefix.length() < LIMIT / 2 ? prefix : "");
                        continue;
                    }
                    int end = Math.max(1, available);
                    if (end < remaining.length() && Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
                    if (end == 0) end = 2;
                    current.append(remaining, 0, end);
                    remaining = remaining.substring(end);
                    result.add(current + (fence.isEmpty() ? "" : "\n" + fenceMarker(fence)));
                    current = new StringBuilder(prefix.length() < LIMIT / 2 ? prefix : "");
                }
            }
            if (marker) {
                if (fence.isEmpty()) fence = trimmed.stripTrailing();
                else if (trimmed.stripTrailing().matches(java.util.regex.Pattern.quote(fenceMarker(fence)) + "[" + fence.charAt(0) + "]*")) fence = "";
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static String fenceMarker(String fence) {
        int end = 0;
        while (end < fence.length() && fence.charAt(end) == fence.charAt(0)) end++;
        return fence.substring(0, end);
    }

    static void validateWebhook(int status, String body) {
        validate(status, body);
        try {
            var code = JSON.readTree(body).get("errcode");
            if (code == null || !code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0)
                throw new IllegalStateException("DingTalk webhook response has no valid success code");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid DingTalk response", e);
        }
    }

    static void validate(int status, String body) {
        if (status < 200 || status >= 300) throw new IllegalStateException("DingTalk HTTP " + status);
        try {
            var node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw new IllegalStateException("Invalid DingTalk response");
            var code = node.get("errcode");
            if (code != null && !"0".equals(code.asText()))
                throw new IllegalStateException("DingTalk rejected message: " + code.asText() + " " + node.path("errmsg").asText());
            if (node.hasNonNull("code") && !"0".equals(node.get("code").asText())
                    && !"OK".equalsIgnoreCase(node.get("code").asText()))
                throw new IllegalStateException("DingTalk rejected message: " + node.get("code").asText());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid DingTalk response", e);
        }
    }
}
