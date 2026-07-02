package vip.mate.tool.local;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Exposes whether the current user has a live desktop tunnel, so the admin UI
 * can show local-tool availability and the connection state.
 *
 * @author MateClaw Team
 */
@RestController
@RequestMapping("/api/v1/desktop")
@RequiredArgsConstructor
public class DesktopBridgeController {

    private final DesktopBridgeRegistry registry;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;

        DesktopBridgeRegistry.DesktopSession session =
                username != null ? registry.getSession(username) : null;
        boolean online = session != null && session.session().isOpen();

        body.put("online", online);
        if (online) {
            body.put("platform", session.platform());
            body.put("protocolVersion", session.protocolVersion());
            body.put("capabilities", session.capabilities());
        }
        return body;
    }
}
