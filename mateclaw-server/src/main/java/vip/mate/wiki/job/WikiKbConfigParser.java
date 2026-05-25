package vip.mate.wiki.job;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses optional machine-readable KB config from JSON or markdown frontmatter.
 */
public final class WikiKbConfigParser {

    private WikiKbConfigParser() {
    }

    public static WikiKbConfig parse(ObjectMapper objectMapper, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{")) {
            try {
                return objectMapper.readValue(trimmed, WikiKbConfig.class);
            } catch (Exception e) {
                return null;
            }
        }
        if (trimmed.startsWith("---")) {
            return parseFrontmatter(trimmed);
        }
        return null;
    }

    private static WikiKbConfig parseFrontmatter(String content) {
        int end = content.indexOf("\n---", 3);
        if (end < 0) return null;

        WikiKbConfig config = new WikiKbConfig();
        Map<String, Long> stepModels = new LinkedHashMap<>();
        String frontmatter = content.substring(3, end);
        for (String line : frontmatter.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            if (value.isBlank()) continue;

            if ("ingestMode".equals(key)) {
                config.setIngestMode(value);
            } else if ("useStructuredRoute".equals(key)) {
                config.setUseStructuredRoute(Boolean.valueOf(value));
            } else if ("wikiDefaultModelId".equals(key)) {
                Long parsed = parseLong(value);
                if (parsed != null) config.setWikiDefaultModelId(parsed);
            } else if (key.startsWith("stepModels.")) {
                Long parsed = parseLong(value);
                if (parsed != null) {
                    stepModels.put(key.substring("stepModels.".length()), parsed);
                }
            }
        }
        if (!stepModels.isEmpty()) {
            config.setStepModels(stepModels);
        }
        return config;
    }

    private static String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
