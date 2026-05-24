package vip.mate.trigger.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.channel.event.ChannelMessageReceivedEvent;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges {@link ChannelMessageReceivedEvent} from the channel module
 * into two trigger pattern types — {@code channel_message} (matches by
 * {@code channelType} / {@code senderEquals}) and {@code content_match}
 * (matches by substring inside the message content). The same envelope
 * fans out to both since the matcher's per-pattern key on the SQL
 * candidate query selects which triggers actually run.
 *
 * <p>Lives in the trigger module so the channel runtime stays free of
 * trigger / ingest dependencies. Failures inside ingest are logged and
 * swallowed — a bad downstream trigger MUST NOT corrupt the primary
 * chat-routing path that just published the event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMessageEventBridge {

    private final TriggerEventIngestService ingestService;

    @Async
    @EventListener
    public void onChannelMessage(ChannelMessageReceivedEvent event) {
        if (event == null) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("channelType", event.channelType());
            data.put("senderId", event.senderId());
            if (event.senderName() != null) data.put("senderName", event.senderName());
            if (event.chatId() != null) data.put("chatId", event.chatId());
            // The matcher's content_match pattern reads `data.content`,
            // so we put the message body there even when it's blank.
            data.put("content", event.content() == null ? "" : event.content());

            // Fan to channel_message pattern triggers.
            ingestService.ingest(new TriggerEventEnvelope(
                    event.workspaceId(),
                    "channel_message",
                    event.messageId(),
                    event.senderId(),
                    data));
            // And to content_match triggers, which live under a different
            // patternType but read the same envelope shape. Two separate
            // ingests instead of one because the SQL candidate query
            // filters on patternType — a single dispatch with one
            // patternType cannot reach the other set.
            ingestService.ingest(new TriggerEventEnvelope(
                    event.workspaceId(),
                    "content_match",
                    event.messageId(),
                    event.senderId(),
                    data));
        } catch (Exception e) {
            log.warn("[ChannelMessageBridge] forwarding message {} from {} failed: {}",
                    event.messageId(), event.senderId(), e.getMessage());
        }
    }
}
