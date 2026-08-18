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
     * one level below: {@code {baseDir}/{conversationId}/} — flat, or with a
     * date level when {@link #dateFolders} is enabled.
     */
    private String baseDir = "data/chat-uploads";

    /**
     * When {@code true} (the default), new attachments and generated media are
     * written under a per-day sub-directory:
     * {@code {conversationDir}/yyyy-MM-dd/{storedName}}. Long-lived
     * conversations (IM channels keep one conversation per chat indefinitely)
     * otherwise accumulate thousands of files in a single flat directory.
     * <p>
     * Serving URLs stay flat ({@code /api/v1/chat/files/{convId}/{storedName}});
     * every read path probes the flat directory first and then each date
     * sub-directory, so files written under either layout remain resolvable and
     * the flag can be toggled at any time without migration.
     * <p>
     * The day comes from the server's local date, so a container running in UTC
     * groups files by UTC days. Reads never depend on it — they scan every date
     * directory — so a timezone change only affects where the next write lands.
     */
    private boolean dateFolders = true;
}
