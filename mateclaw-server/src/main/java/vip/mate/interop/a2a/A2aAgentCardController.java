package vip.mate.interop.a2a;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class A2aAgentCardController {

    private final A2aAgentCardService cardService;

    @GetMapping("/api/a2a/card")
    public Map<String, Object> card(HttpServletRequest request,
                                    @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                                    Authentication authentication) {
        if (authentication == null) {
            return cardService.publicCard(request);
        }
        return cardService.authenticatedCard(request, workspaceId);
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> wellKnown(HttpServletRequest request) {
        return cardService.publicCard(request);
    }
}
