package vip.mate.interop.a2a;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class A2aAgentCardService {

    private final A2aProperties properties;
    private final AgentService agentService;

    public Map<String, Object> publicCard(HttpServletRequest request) {
        Map<String, Object> card = baseCard(request);
        card.put("supportsAuthenticatedExtendedCard", true);
        card.remove("skills");
        return card;
    }

    public Map<String, Object> authenticatedCard(HttpServletRequest request, Long workspaceId) {
        long wsId = workspaceId == null ? 1L : workspaceId;
        Map<String, Object> card = baseCard(request);
        List<Map<String, Object>> skills = new ArrayList<>();
        for (AgentEntity agent : agentService.listAgentsByWorkspace(wsId, true)) {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("id", String.valueOf(agent.getId()));
            skill.put("name", agent.getName());
            skill.put("description", agent.getDescription() == null ? "" : agent.getDescription());
            skill.put("tags", tags(agent.getTags()));
            skills.add(skill);
        }
        card.put("skills", skills);
        return card;
    }

    private Map<String, Object> baseCard(HttpServletRequest request) {
        String rpcUrl = externalBaseUrl(request).replaceAll("/+$", "") + "/api/a2a";
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "MateClaw");
        card.put("description", "A multi-agent runtime exposed through A2A JSON-RPC.");
        card.put("url", rpcUrl);
        card.put("version", "1.0.0");
        card.put("protocolVersion", "1.0");
        card.put("supportedInterfaces", List.of(Map.of(
                "url", rpcUrl,
                "protocolBinding", "JSONRPC",
                "protocolVersion", "1.0"
        )));
        card.put("capabilities", Map.of(
                "streaming", true,
                "pushNotifications", false,
                "stateTransitionHistory", false
        ));
        card.put("defaultInputModes", List.of("text/plain"));
        card.put("defaultOutputModes", List.of("text/plain"));
        card.put("skills", List.of());
        return card;
    }

    private String externalBaseUrl(HttpServletRequest request) {
        if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            return properties.getBaseUrl().trim();
        }
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private static List<String> tags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
