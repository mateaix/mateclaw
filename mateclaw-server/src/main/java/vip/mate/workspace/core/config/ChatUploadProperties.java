package vip.mate.workspace.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the chat-attachment upload directory.
 * <p>
 * Chat uploads (files exchanged in a conversation) resolve their storage root
 * with this precedence:
 * <ol>
 *   <li>Agent-level {@code workspaceBasePath} override (resolved under the
 *       workspace {@code basePath}, same rule as
 *       {@code AgentGraphBuilder.resolveAgentBasePath});</li>
 *   <li>Workspace {@code basePath} (when the agent has no override);</li>
 *   <li>This {@link #baseDir} fallback — the out-of-the-box default used when
 *       neither the agent nor its workspace configures a base path.</li>
 * </ol>
 * The default keeps the legacy {@code data/chat-uploads} location so existing
 * single-workspace deployments see no behavioural change.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.chat.upload")
public class ChatUploadProperties {

    /**
     * Root directory for chat attachments when neither the active agent nor its
     * workspace configures a base path. Defaults to {@code data/chat-uploads}
     * (relative to the Spring Boot working directory). Conversations are stored
     * one level below: {@code {baseDir}/{conversationId}/{storedName}}.
     */
    private String baseDir = "data/chat-uploads";
}
