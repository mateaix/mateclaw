package vip.mate.channel.wecom.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.approval.ApprovalService;
import vip.mate.channel.wecom.cards.WeComCardKind;

/**
 * Spring-managed factory that produces the tool-guard card kind for
 * {@link vip.mate.channel.wecom.cards.WeComCardDispatcher}.
 *
 * <p>Plain {@code @Component} so the dispatcher can constructor-inject
 * it. Each call to {@link #create()} returns a freshly constructed
 * {@link WeComCardKind}; the dispatcher keeps the result and queries it
 * for life of the JVM.
 */
@Component
public class ToolGuardCardKindFactory {

    /**
     * Same value as {@link ToolGuardCardRenderer#TASK_ID_PREFIX}. Kept
     * here too so the {@link WeComCardKind#taskIdPrefix()} index can be
     * declared from the factory without reaching into the renderer.
     */
    public static final String TASK_ID_PREFIX = ToolGuardCardRenderer.TASK_ID_PREFIX;

    /**
     * Outbound metadata.message_type matched by the dispatcher when the
     * agent runtime emits an approval-pending event. Currently the WeCom
     * adapter's sendApprovalNotice override doesn't read message_type
     * (it always renders tool-guard), but having the index lets future
     * card kinds plug in cleanly.
     */
    public static final String MESSAGE_TYPE = "tool_guard_approval";

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    public ToolGuardCardKindFactory(ApprovalService approvalService, ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
    }

    public WeComCardKind create() {
        ToolGuardButtonKey buttonKey = new ToolGuardButtonKey(objectMapper);
        ToolGuardCardRenderer renderer = new ToolGuardCardRenderer(buttonKey);
        ToolGuardCardHandler handler = new ToolGuardCardHandler(approvalService, buttonKey);
        return new WeComCardKind(
                "tool_guard_approval",
                MESSAGE_TYPE,
                TASK_ID_PREFIX,
                renderer,
                handler
        );
    }
}
