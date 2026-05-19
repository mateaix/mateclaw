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
}
