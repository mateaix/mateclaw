package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FeishuCardFormatter {

    enum ContentFormat { JSON, MARKDOWN, LONG_TEXT, PLAIN_TEXT }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int JSON_MAX_LEN = 32_000;
    private static final Pattern HEADER    = Pattern.compile("(?m)^#{1,6}\\s");
    private static final Pattern TABLE_SEP = Pattern.compile("(?m)^\\|[\\s|:-]+\\|\\s*$");
    private static final Pattern JSON_CODE_BLOCK =
            Pattern.compile("(?s)```(?:json)?\\s*([\\[{][\\s\\S]*?[\\]}])\\s*```");

    private FeishuCardFormatter() {}

    static ContentFormat detect(String content) {
        if (content == null || content.isBlank()) return ContentFormat.PLAIN_TEXT;
        String s = content.trim();

        if ((s.startsWith("{") || s.startsWith("[")) && s.length() <= JSON_MAX_LEN) {
            try {
                JsonNode node = MAPPER.readTree(s);
                if (node.isObject() && !node.isEmpty()) return ContentFormat.JSON;
                if (node.isArray() && node.size() > 0 && node.get(0).isObject()) return ContentFormat.JSON;
            } catch (Exception ignored) {}
        }

        if (s.contains("```")) {
            Matcher cm = JSON_CODE_BLOCK.matcher(s);
            while (cm.find()) {
                String extracted = cm.group(1).strip();
                if (extracted.length() <= JSON_MAX_LEN) {
                    try {
                        JsonNode node = MAPPER.readTree(extracted);
                        if (node.isObject() && !node.isEmpty()) return ContentFormat.JSON;
                        if (node.isArray() && node.size() > 0 && node.get(0).isObject()) return ContentFormat.JSON;
                    } catch (Exception ignored) {}
                }
            }
            return ContentFormat.MARKDOWN;
        }
        if (HEADER.matcher(s).find())         return ContentFormat.MARKDOWN;
        if (TABLE_SEP.matcher(s).find())      return ContentFormat.MARKDOWN;
        if (bulletCount(s) >= 2)              return ContentFormat.MARKDOWN;

        if (s.length() > 300 && s.contains("\n\n")) return ContentFormat.LONG_TEXT;

        return ContentFormat.PLAIN_TEXT;
    }

    private static long bulletCount(String s) {
        return s.lines()
                .filter(line -> {
                    String t = line.stripLeading();
                    return t.startsWith("- ") || t.startsWith("* ")
                            || t.matches("^\\d+\\.\\s.*");
                })
                .count();
    }

    // ==================== 渲染层 ====================

    /** Default markdown card header used when the channel doesn't override it. */
    static final String DEFAULT_MARKDOWN_HEADER = "AI 助手";

    static Map<String, Object> render(String content, ContentFormat format) {
        return render(content, format, DEFAULT_MARKDOWN_HEADER);
    }

    /**
     * Renders the card for the given content/format pair. {@code markdownHeader}
     * controls the title shown above markdown cards — pass null or blank to
     * suppress the header entirely. JSON and long-text/plain-text layouts ignore
     * the header (they have never carried one).
     */
    static Map<String, Object> render(String content, ContentFormat format, String markdownHeader) {
        return switch (format) {
            case JSON      -> renderJson(content);
            case MARKDOWN  -> renderMarkdown(content, markdownHeader);
            case LONG_TEXT, PLAIN_TEXT -> renderLongText(content);
        };
    }

    private static Map<String, Object> renderMarkdown(String content, String headerText) {
        Map<String, Object> header = (headerText == null || headerText.isBlank())
                ? null
                : Map.of("title", Map.of("tag", "plain_text", "content", headerText));
        return cardOf(
            header,
            List.of(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", content)
            ))
        );
    }

    private static Map<String, Object> renderLongText(String content) {
        return cardOf(
            null,
            List.of(Map.of(
                "tag", "div",
                "text", Map.of("tag", "plain_text", "content", content)
            ))
        );
    }

    private static Map<String, Object> renderJson(String content) {
        try {
            JsonNode node = MAPPER.readTree(content);
            if (node.isObject()) return renderJsonObject(node);
            if (node.isArray())  return renderJsonArray(node);
        } catch (Exception ignored) {}

        Matcher rm = JSON_CODE_BLOCK.matcher(content);
        while (rm.find()) {
            String extracted = rm.group(1).strip();
            try {
                JsonNode node = MAPPER.readTree(extracted);
                if (node.isObject()) return renderJsonObject(node);
                if (node.isArray())  return renderJsonArray(node);
            } catch (Exception ignored) {}
        }
        return renderLongText(content);
    }

    private static Map<String, Object> renderJsonObject(JsonNode node) {
        List<Object> elements = new ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String value = entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue().toString();
            elements.add(Map.of(
                "tag", "column_set",
                "flex_mode", "none",
                "columns", List.of(
                    Map.of("tag", "column", "width", "weighted", "weight", 1,
                        "elements", List.of(Map.of("tag", "div",
                            "text", Map.of("tag", "plain_text", "content", key)))),
                    Map.of("tag", "column", "width", "weighted", "weight", 2,
                        "elements", List.of(Map.of("tag", "div",
                            "text", Map.of("tag", "plain_text", "content", value))))
                )
            ));
        });
        return cardOf(null, elements);
    }

    private static Map<String, Object> renderJsonArray(JsonNode array) {
        JsonNode first = array.get(0);
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        return fields.size() <= 4
                ? renderJsonTable(array, fields)
                : renderJsonList(array);
    }

    private static Map<String, Object> renderJsonTable(JsonNode array, List<String> fields) {
        List<Map<String, Object>> columns = fields.stream()
                .<Map<String, Object>>map(name -> Map.of("name", name, "display_name", name))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : fields) {
                JsonNode val = item.get(field);
                row.put(field, val == null ? "" : (val.isTextual() ? val.asText() : val.toString()));
            }
            rows.add(row);
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("tag", "table");
        table.put("columns", columns);
        table.put("rows", rows);
        table.put("page_size", 10);
        table.put("row_height", "low");
        return cardOf(null, List.of(table));
    }

    private static Map<String, Object> renderJsonList(JsonNode array) {
        List<Object> elements = new ArrayList<>();
        for (JsonNode item : array) {
            StringBuilder sb = new StringBuilder();
            item.fields().forEachRemaining(e -> {
                String val = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
                sb.append("**").append(e.getKey()).append("**: ").append(val).append("\n");
            });
            elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", sb.toString().trim())
            ));
        }
        return cardOf(null, elements);
    }

    private static Map<String, Object> cardOf(Map<String, Object> header, List<?> elements) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of("wide_screen_mode", true));
        if (header != null) card.put("header", header);
        card.put("body", Map.of("elements", elements));
        return card;
    }
}
