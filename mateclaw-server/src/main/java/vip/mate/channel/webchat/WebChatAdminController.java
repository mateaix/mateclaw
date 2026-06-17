package vip.mate.channel.webchat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.common.result.R;

import java.util.Map;

/**
 * Admin-facing webchat operations. Mounted under {@code /api/v1/admin/webchat/**}
 * (outside the {@code /api/v1/channels/webchat/**} permitAll block) so it
 * requires a regular MateClaw JWT — visitors cannot reach these endpoints.
 *
 * <p>Currently only manages visitor-token revocation. Audit-recorded via
 * {@link AuditEventService}; actor is the JWT-authenticated admin username,
 * not the visitor.
 *
 * @author MateClaw Team
 */
@Tag(name = "WebChat 管理(管理员)")
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/webchat")
@RequiredArgsConstructor
public class WebChatAdminController {

    private final ChannelService channelService;
    private final WebChatTokenRevocationService revocationService;
    private final AuditEventService auditService;

    @Operation(summary = "撤销访客的 visitorToken(ban 该 visitor 在管理端点的所有调用)")
    @PostMapping("/revoked-visitor")
    public R<Void> revokeVisitor(
            @RequestBody RevokeVisitorRequest request,
            Authentication auth) {
        if (request == null || request.getChannelId() == null
                || request.getVisitorId() == null || request.getVisitorId().isBlank()) {
            return R.fail(400, "channelId and visitorId are required");
        }
        ChannelEntity channel = channelService.getChannel(request.getChannelId());
        if (channel == null || !"webchat".equals(channel.getChannelType())) {
            return R.fail(404, "webchat channel not found");
        }
        revocationService.revoke(channel.getId(), request.getVisitorId().trim(),
                request.getReason());

        String adminUser = auth != null ? auth.getName() : "system";
        auditService.record(
                "webchat.revoke-visitor",
                "CHANNEL",
                String.valueOf(channel.getId()),
                channel.getName(),
                "{\"visitorId\":\"" + request.getVisitorId().trim()
                        + "\",\"reason\":\"" + (request.getReason() != null ? request.getReason() : "")
                        + "\",\"admin\":\"" + adminUser + "\"}",
                channel.getWorkspaceId());
        return R.ok();
    }

    @Operation(summary = "取消撤销访客(un-ban)")
    @DeleteMapping("/revoked-visitor")
    public R<Void> unrevokeVisitor(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long channelId = body.get("channelId") instanceof Number n ? n.longValue() : null;
        Object rawChannelId = body.get("channelId");
        if (rawChannelId instanceof String s && !s.isBlank()) {
            try { channelId = Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        String visitorId = body.get("visitorId") instanceof String s ? s.trim() : null;
        if (channelId == null || visitorId == null || visitorId.isEmpty()) {
            return R.fail(400, "channelId and visitorId are required");
        }
        revocationService.unrevoke(channelId, visitorId);

        String adminUser = auth != null ? auth.getName() : "system";
        auditService.record(
                "webchat.unrevoke-visitor",
                "CHANNEL",
                String.valueOf(channelId),
                null,
                "{\"visitorId\":\"" + visitorId + "\",\"admin\":\"" + adminUser + "\"}",
                null);
        return R.ok();
    }

    @lombok.Data
    public static class RevokeVisitorRequest {
        private Long channelId;
        private String visitorId;
        private String reason;
    }
}
