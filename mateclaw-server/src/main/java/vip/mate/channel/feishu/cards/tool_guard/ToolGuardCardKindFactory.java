package vip.mate.channel.feishu.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.approval.ApprovalService;
import vip.mate.channel.feishu.cards.FeishuCardKind;

/**
 * Spring-managed factory that produces the tool-guard card kind for
 * {@link vip.mate.channel.feishu.cards.FeishuCardDispatcher}.
 *
 * <p>Plain {@code @Component} so the dispatcher can constructor-inject
 * it. Each call to {@link #create()} returns a freshly constructed
 * {@link FeishuCardKind}; the dispatcher caches the result and queries
 * it for life of the JVM.
 */
@Component("feishuToolGuardCardKindFactory")
public class ToolGuardCardKindFactory {

    /**
     * Kind name — used by callers that look up the kind by name for
     * outbound render (today only {@code FeishuChannelAdapter.sendApprovalNotice}).
     */
    public static final String KIND_NAME = "tool_guard_approval";

    /** Discriminator prefix on inbound {@code action.value.action}. */
    public static final String ACTION_PREFIX = ToolGuardButtonValue.ACTION_PREFIX;

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    public ToolGuardCardKindFactory(ApprovalService approvalService,
                                     ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
    }

    public FeishuCardKind create() {
        ToolGuardButtonValue buttonValue = new ToolGuardButtonValue(objectMapper);
        ToolGuardCardRenderer renderer = new ToolGuardCardRenderer(buttonValue);
        // Handler no longer needs ApprovalWorkflowService — the canonical
        // resolve + replay path runs via a synthetic /approve|/deny
        // message injected back into the router.
        ToolGuardCardHandler handler = new ToolGuardCardHandler(approvalService, buttonValue);
        return new FeishuCardKind(KIND_NAME, ACTION_PREFIX, renderer, handler);
    }
}
