package vip.mate.channel.feishu.tool;

import vip.mate.channel.tool.ChannelToolDescriptor;

import java.util.List;

/**
 * Static catalog of Feishu channel-native tools. Kept separate from
 * the provider so the descriptors can be queried by tests / docs
 * without instantiating the provider (which would drag in the SDK
 * client factory).
 *
 * <p>Tool naming: the base name registered here is the "human"
 * identifier. {@code ChannelToolService} appends {@code _c<channelId>}
 * before registering with {@code ToolRegistry}, so the actual name an
 * Agent sees is e.g. {@code feishu_doc_create_c2055137662148763649}.
 *
 * <p>Initial set covers the most useful read + a representative write
 * per resource family — the rest land as follow-ups:
 * <ul>
 *   <li><b>feishu_calendar_list_events</b> — read; default-on</li>
 *   <li><b>feishu_doc_read</b> — read; default-on</li>
 *   <li><b>feishu_doc_create</b> — write; default-off, approval-gated</li>
 * </ul>
 */
public final class FeishuToolCatalog {

    public static final String TOOL_LIST_EVENTS = "feishu_calendar_list_events";
    public static final String TOOL_DOC_READ = "feishu_doc_read";
    public static final String TOOL_DOC_CREATE = "feishu_doc_create";

    private FeishuToolCatalog() {}

    public static List<ChannelToolDescriptor> descriptors() {
        return List.of(
                new ChannelToolDescriptor(
                        TOOL_LIST_EVENTS,
                        "List Feishu calendar events",
                        "List events on a Feishu calendar within a time window. "
                                + "Required: calendar_id (the user's primary calendar id is usually returned by "
                                + "the calendar.primary endpoint). Optional: start_time (UNIX seconds string), "
                                + "end_time (UNIX seconds string), page_size (1-1000, default 100).",
                        eventsListSchema(),
                        /* mutating */ false, /* enabledByDefault */ true),

                new ChannelToolDescriptor(
                        TOOL_DOC_READ,
                        "Read a Feishu Doc as plain text",
                        "Fetch a Feishu Doc's raw plain-text content. Required: document_id "
                                + "(the {documentId} segment in the URL https://x.feishu.cn/docx/{documentId}). "
                                + "Returns the raw concatenated text — no formatting / images / tables.",
                        docReadSchema(),
                        /* mutating */ false, /* enabledByDefault */ true),

                new ChannelToolDescriptor(
                        TOOL_DOC_CREATE,
                        "Create a new Feishu Doc",
                        "Create an empty Feishu Doc. Required: title (string). Optional: folder_token "
                                + "(target folder; empty string = root). Returns the new doc's "
                                + "{document_id, url}. NOTE: only the bot's app sees the new doc until "
                                + "you explicitly share it — pass owner_open_id later via a permission "
                                + "tool to grant access. This is a write tool and triggers an approval.",
                        docCreateSchema(),
                        /* mutating */ true, /* enabledByDefault */ false)
        );
    }

    // ------------------------------------------------------------------
    // JSON Schemas — kept as constants so the descriptor is pure data
    // ------------------------------------------------------------------

    private static String eventsListSchema() {
        return "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"calendar_id\":{\"type\":\"string\",\"description\":\"target calendar id\"},"
                + "\"start_time\":{\"type\":\"string\",\"description\":\"UNIX seconds, lower bound\"},"
                + "\"end_time\":{\"type\":\"string\",\"description\":\"UNIX seconds, upper bound\"},"
                + "\"page_size\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000,\"default\":100}"
                + "},\"required\":[\"calendar_id\"]}";
    }

    private static String docReadSchema() {
        return "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"document_id\":{\"type\":\"string\",\"description\":\"the {documentId} segment of the doc URL\"}"
                + "},\"required\":[\"document_id\"]}";
    }

    private static String docCreateSchema() {
        return "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"description\":\"document title\"},"
                + "\"folder_token\":{\"type\":\"string\",\"description\":\"target folder token; empty = root\"}"
                + "},\"required\":[\"title\"]}";
    }
}
