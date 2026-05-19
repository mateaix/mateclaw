package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

final class FeishuCardFormatter {

    enum ContentFormat { JSON, MARKDOWN, LONG_TEXT, PLAIN_TEXT }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int JSON_MAX_LEN = 32_000;
    private static final Pattern HEADER    = Pattern.compile("(?m)^#{1,6}\\s");
    private static final Pattern TABLE_SEP = Pattern.compile("(?m)^\\|[\\s|:-]+\\|\\s*$");

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

        if (s.contains("```"))               return ContentFormat.MARKDOWN;
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

    static java.util.Map<String, Object> render(String content, ContentFormat format) {
        return switch (format) {
            case JSON      -> renderJson(content);
            case MARKDOWN  -> renderMarkdown(content);
            case LONG_TEXT, PLAIN_TEXT -> renderLongText(content);
        };
    }

    private static java.util.Map<String, Object> renderMarkdown(String content) {
        return cardOf(
            java.util.Map.of("title", java.util.Map.of("tag", "plain_text", "content", "AI 助手")),
            java.util.List.of(java.util.Map.of(
                "tag", "div",
                "text", java.util.Map.of("tag", "lark_md", "content", content)
            ))
        );
    }

    private static java.util.Map<String, Object> renderLongText(String content) {
        return cardOf(
            null,
            java.util.List.of(java.util.Map.of(
                "tag", "div",
                "text", java.util.Map.of("tag", "plain_text", "content", content)
            ))
        );
    }

    private static java.util.Map<String, Object> renderJson(String content) {
        try {
            JsonNode node = MAPPER.readTree(content);
            if (node.isObject()) return renderJsonObject(node);
            if (node.isArray())  return renderJsonArray(node);
        } catch (Exception ignored) {}
        return renderLongText(content);
    }

    private static java.util.Map<String, Object> renderJsonObject(JsonNode node) {
        java.util.List<Object> elements = new java.util.ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String value = entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue().toString();
            elements.add(java.util.Map.of(
                "tag", "column_set",
                "flex_mode", "none",
                "columns", java.util.List.of(
                    java.util.Map.of("tag", "column", "width", "weighted", "weight", 1,
                        "elements", java.util.List.of(java.util.Map.of("tag", "div",
                            "text", java.util.Map.of("tag", "plain_text", "content", key)))),
                    java.util.Map.of("tag", "column", "width", "weighted", "weight", 2,
                        "elements", java.util.List.of(java.util.Map.of("tag", "div",
                            "text", java.util.Map.of("tag", "plain_text", "content", value))))
                )
            ));
        });
        return cardOf(null, elements);
    }

    private static java.util.Map<String, Object> renderJsonArray(JsonNode array) {
        JsonNode first = array.get(0);
        java.util.List<String> fields = new java.util.ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        return fields.size() <= 4
                ? renderJsonTable(array, fields)
                : renderJsonList(array);
    }

    private static java.util.Map<String, Object> renderJsonTable(JsonNode array, java.util.List<String> fields) {
        java.util.List<java.util.Map<String, Object>> columns = fields.stream()
                .<java.util.Map<String, Object>>map(name -> java.util.Map.of("name", name, "display_name", name))
                .toList();
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (String field : fields) {
                JsonNode val = item.get(field);
                row.put(field, val == null ? "" : (val.isTextual() ? val.asText() : val.toString()));
            }
            rows.add(row);
        }
        java.util.Map<String, Object> table = new java.util.LinkedHashMap<>();
        table.put("tag", "table");
        table.put("columns", columns);
        table.put("rows", rows);
        table.put("page_size", 10);
        table.put("row_height", "low");
        return cardOf(null, java.util.List.of(table));
    }

    private static java.util.Map<String, Object> renderJsonList(JsonNode array) {
        java.util.List<Object> elements = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            StringBuilder sb = new StringBuilder();
            item.fields().forEachRemaining(e -> {
                String val = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
                sb.append("**").append(e.getKey()).append("**: ").append(val).append("\n");
            });
            elements.add(java.util.Map.of(
                "tag", "div",
                "text", java.util.Map.of("tag", "lark_md", "content", sb.toString().trim())
            ));
        }
        return cardOf(null, elements);
    }

    private static java.util.Map<String, Object> cardOf(java.util.Map<String, Object> header, java.util.List<?> elements) {
        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", java.util.Map.of("wide_screen_mode", true));
        if (header != null) card.put("header", header);
        card.put("body", java.util.Map.of("elements", elements));
        return card;
    }
}
