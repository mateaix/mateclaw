package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.mate.team.model.TeamTaskCreateCommand;

import java.nio.charset.StandardCharsets;

/** Creation-time delegation context. Live feedback and attachments remain outside this snapshot. */
public final class TeamTaskBriefing {
    public static final int MAX_OBJECTIVE_CHARS = 4000;
    public static final int MAX_DESCRIPTION_CHARS = 12000;
    public static final int MAX_ACCEPTANCE_CRITERIA_CHARS = 4000;
    public static final int MAX_CONSTRAINTS_CHARS = 4000;

    public static final int MAX_METADATA_BYTES = 65535;

    private TeamTaskBriefing() {}

    public static String capture(TeamTaskCreateCommand command, String objective) {
        JSONObject metadata;
        try {
            metadata = command.getMetadata() == null ? new JSONObject() : JSONUtil.parseObj(command.getMetadata());
        } catch (RuntimeException ignored) {
            metadata = new JSONObject();
        }
        JSONObject briefing = new JSONObject();
        briefing.set("schemaVersion", 1);
        put(briefing, "objective", objective, MAX_OBJECTIVE_CHARS);
        put(briefing, "description", command.getDescription(), MAX_DESCRIPTION_CHARS);
        put(briefing, "acceptanceCriteria", command.getAcceptanceCriteria(), MAX_ACCEPTANCE_CRITERIA_CHARS);
        put(briefing, "constraints", command.getConstraints(), MAX_CONSTRAINTS_CHARS);
        metadata.set("briefing", briefing);
        String serialized = metadata.toString();
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("task metadata exceeds 65535 UTF-8 bytes including the saved briefing; reduce metadata or task instructions");
        }
        return serialized;
    }

    private static void put(JSONObject target, String key, String value, int limit) {
        if (value == null) return;
        // Each field uses the same numeric limit for UTF-16 characters and UTF-8 bytes.
        if (value.length() <= limit && value.getBytes(StandardCharsets.UTF_8).length <= limit) {
            target.set(key, value);
            return;
        }
        int end = 0;
        int bytes = 0;
        int byteBudget = limit - "…".getBytes(StandardCharsets.UTF_8).length;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int chars = Character.charCount(codePoint);
            int encodedBytes = value.substring(end, end + chars).getBytes(StandardCharsets.UTF_8).length;
            if (end + chars > limit - 1 || bytes + encodedBytes > byteBudget) break;
            end += chars;
            bytes += encodedBytes;
        }
        target.set(key, value.substring(0, end) + "…");
    }

    /** Returns false for absent, unknown, malformed or oversized legacy snapshots. */
    public static boolean appendTo(StringBuilder content, String metadata) {
        try {
            Object raw = JSONUtil.parseObj(metadata).get("briefing");
            if (!(raw instanceof JSONObject briefing)) return false;
            Object version = briefing.get("schemaVersion");
            if (!(version instanceof Number) || !"1".equals(version.toString())) return false;
            if (!valid(briefing, "objective", MAX_OBJECTIVE_CHARS)
                    || !valid(briefing, "description", MAX_DESCRIPTION_CHARS)
                    || !valid(briefing, "acceptanceCriteria", MAX_ACCEPTANCE_CRITERIA_CHARS)
                    || !valid(briefing, "constraints", MAX_CONSTRAINTS_CHARS)) return false;
            append(content, "Run objective", briefing.getStr("objective"));
            append(content, "Task description", briefing.getStr("description"));
            append(content, "Acceptance criteria", briefing.getStr("acceptanceCriteria"));
            append(content, "Constraints", briefing.getStr("constraints"));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean valid(JSONObject briefing, String key, int limit) {
        Object value = briefing.get(key);
        return value == null || JSONUtil.isNull(value) || value instanceof String text && text.length() <= limit;
    }

    private static void append(StringBuilder content, String label, String value) {
        if (value != null && !value.isBlank()) content.append("\n[").append(label).append("]\n").append(value).append('\n');
    }
}
