package vip.mate.workspace.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vip.mate.tool.builtin.ChatUploadResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Normalizes and pre-creates the default chat-upload directory on startup.
 * <p>
 * Mirrors {@link WorkspaceSandboxAutoConfiguration}: the directory is created
 * eagerly so the first upload does not race on {@code Files.createDirectories},
 * and a missing/blank value restores the legacy {@code data/chat-uploads}. The
 * normalized default is also registered with the static
 * {@link ChatUploadResolver} so the tool-side fallback lookup agrees with the
 * Spring-managed {@link ChatUploadLocationResolver}.
 *
 * @author MateClaw Team
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ChatUploadProperties.class)
public class ChatUploadAutoConfiguration {

    public ChatUploadAutoConfiguration(ChatUploadProperties properties) {
        String raw = properties.getBaseDir();
        if (raw == null || raw.isBlank()) {
            raw = "data/chat-uploads";
            properties.setBaseDir(raw);
        }
        Path root = Paths.get(raw).toAbsolutePath().normalize();
        properties.setBaseDir(root.toString());
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            // The first upload will retry createDirectories; log and continue
            // rather than fail startup.
            log.warn("[ChatUpload] Failed to create default upload dir {}: {}",
                    root, e.getMessage());
        }
        ChatUploadResolver.setDefaultRoot(root);
        log.info("[ChatUpload] Default upload dir: {}", root);
    }
}
