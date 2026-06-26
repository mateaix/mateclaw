package vip.mate.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders a {@link LocalToolBridgeService.BridgeResult} into the JSON string a
 * {@code local_*} tool returns to the agent. Successful calls pass the desktop
 * payload through verbatim; failures become a uniform error object the LLM can
 * reason about ({@code error}, {@code code}, {@code message}).
 *
 * @author MateClaw Team
 */
final class LocalToolFormat {

    private LocalToolFormat() {
    }

    static String render(LocalToolBridgeService.BridgeResult result, ObjectMapper om) {
        try {
            if (result.ok()) {
                JsonNode data = result.data();
                if (data == null || data.isNull() || data.isMissingNode()) {
                    ObjectNode ok = om.createObjectNode();
                    ok.put("ok", true);
                    return om.writerWithDefaultPrettyPrinter().writeValueAsString(ok);
                }
                return om.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            }
            ObjectNode err = om.createObjectNode();
            err.put("error", true);
            err.put("code", result.code());
            err.put("message", result.error());
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(err);
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"Failed to render local tool result\"}";
        }
    }
}
