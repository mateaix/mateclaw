package vip.mate.channel.feishu.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.calendar.v4.model.CalendarEvent;
import com.lark.oapi.service.calendar.v4.model.ListCalendarEventReq;
import com.lark.oapi.service.calendar.v4.model.ListCalendarEventResp;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentResp;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentReq;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import vip.mate.channel.feishu.FeishuClientFactory;
import vip.mate.channel.tool.ChannelToolCallback;
import vip.mate.channel.tool.ChannelToolContext;
import vip.mate.channel.tool.ChannelToolDescriptor;
import vip.mate.channel.tool.ChannelToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First concrete {@link ChannelToolProvider} — exposes a representative
 * subset of Feishu's OpenAPI as Agent tools:
 *
 * <ul>
 *   <li>{@code feishu_calendar_list_events} (read) —
 *       {@code calendar/v4 calendarEvent.list}</li>
 *   <li>{@code feishu_doc_read} (read) —
 *       {@code docx/v1 document.rawContent}</li>
 *   <li>{@code feishu_doc_create} (write) —
 *       {@code docx/v1 document.create}</li>
 * </ul>
 *
 * <p>Both reads ship default-enabled; the write ships default-disabled
 * and {@code ChannelToolService} seeds a HIGH-severity guard rule so a
 * call falls into {@code NEEDS_APPROVAL} via {@code DbRuleGuardian}.
 *
 * <p>Tool I/O uses JSON — input is the tool's argument JSON string,
 * output is a compact JSON result (success or {@code "error"} key).
 * Returning a structured object rather than free text keeps the LLM
 * downstream branch-friendly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuChannelToolProvider implements ChannelToolProvider {

    private final FeishuClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public List<ChannelToolDescriptor> describeTools() {
        return FeishuToolCatalog.descriptors();
    }

    @Override
    public List<ToolCallback> createTools(ChannelToolContext context) {
        Long channelId = context.channelId();
        List<ToolCallback> out = new ArrayList<>(3);

        for (ChannelToolDescriptor d : FeishuToolCatalog.descriptors()) {
            out.add(new ChannelToolCallback(
                    d.name(), d.description(), d.inputSchema(),
                    input -> dispatch(d.name(), channelId, input)));
        }
        return out;
    }

    /** Route by tool name. Kept in one place so the descriptor catalog drives the surface. */
    private String dispatch(String toolName, Long channelId, String input) {
        try {
            Client client = clientFactory.client(channelId);
            return switch (toolName) {
                case FeishuToolCatalog.TOOL_LIST_EVENTS -> handleListEvents(client, input);
                case FeishuToolCatalog.TOOL_DOC_READ -> handleDocRead(client, input);
                case FeishuToolCatalog.TOOL_DOC_CREATE -> handleDocCreate(client, input);
                default -> errorJson("Unknown Feishu tool: " + toolName);
            };
        } catch (Exception e) {
            log.warn("[feishu-tool] {} failed: {}", toolName, e.getMessage());
            return errorJson(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private String handleListEvents(Client client, String input) throws Exception {
        JsonNode args = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        String calendarId = textArg(args, "calendar_id");
        if (calendarId == null) return errorJson("calendar_id is required");

        ListCalendarEventReq.Builder req = ListCalendarEventReq.newBuilder().calendarId(calendarId);
        String startTime = textArg(args, "start_time");
        String endTime = textArg(args, "end_time");
        Integer pageSize = intArg(args, "page_size");
        if (startTime != null) req.startTime(startTime);
        if (endTime != null) req.endTime(endTime);
        if (pageSize != null) req.pageSize(pageSize);

        ListCalendarEventResp resp = client.calendar().v4().calendarEvent().list(req.build());
        if (!resp.success() || resp.getData() == null) {
            return errorJson("calendar list failed: code=" + resp.getCode() + ", msg=" + resp.getMsg());
        }
        CalendarEvent[] items = resp.getData().getItems();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items == null ? 0 : items.length);
        // Compact representation — title + start + end keeps the LLM context tight.
        List<Map<String, Object>> events = new ArrayList<>();
        if (items != null) {
            for (CalendarEvent ev : items) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("event_id", ev.getEventId());
                e.put("summary", ev.getSummary());
                if (ev.getStartTime() != null) e.put("start", ev.getStartTime().getTimestamp());
                if (ev.getEndTime() != null) e.put("end", ev.getEndTime().getTimestamp());
                events.add(e);
            }
        }
        result.put("events", events);
        return objectMapper.writeValueAsString(result);
    }

    private String handleDocRead(Client client, String input) throws Exception {
        JsonNode args = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        String documentId = textArg(args, "document_id");
        if (documentId == null) return errorJson("document_id is required");

        RawContentDocumentReq req = RawContentDocumentReq.newBuilder().documentId(documentId).build();
        RawContentDocumentResp resp = client.docx().v1().document().rawContent(req);
        if (!resp.success() || resp.getData() == null) {
            return errorJson("doc read failed: code=" + resp.getCode() + ", msg=" + resp.getMsg());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_id", documentId);
        result.put("content", resp.getData().getContent());
        return objectMapper.writeValueAsString(result);
    }

    private String handleDocCreate(Client client, String input) throws Exception {
        JsonNode args = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        String title = textArg(args, "title");
        if (title == null) return errorJson("title is required");
        String folderToken = textArg(args, "folder_token");

        CreateDocumentReqBody.Builder body = CreateDocumentReqBody.newBuilder().title(title);
        if (folderToken != null && !folderToken.isBlank()) {
            body.folderToken(folderToken);
        }
        CreateDocumentReq req = CreateDocumentReq.newBuilder()
                .createDocumentReqBody(body.build())
                .build();
        CreateDocumentResp resp = client.docx().v1().document().create(req);
        if (!resp.success() || resp.getData() == null || resp.getData().getDocument() == null) {
            return errorJson("doc create failed: code=" + resp.getCode() + ", msg=" + resp.getMsg());
        }
        String docId = resp.getData().getDocument().getDocumentId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_id", docId);
        result.put("revision_id", resp.getData().getDocument().getRevisionId());
        result.put("title", title);
        // The URL is constructed client-side; SDK doesn't return it.
        return objectMapper.writeValueAsString(result);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String textArg(JsonNode args, String key) {
        if (args == null) return null;
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        String v = n.asText("");
        return v.isBlank() ? null : v;
    }

    private Integer intArg(JsonNode args, String key) {
        if (args == null) return null;
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        if (n.canConvertToInt()) return n.intValue();
        try {
            return Integer.parseInt(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }
}
