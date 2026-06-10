package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Human-in-the-Loop service for memory editing.
 * <p>
 * When a user edits a memory entry, this service writes it back to the target
 * memory file (MEMORY.md, PROFILE.md, SOUL.md, ...) with a hidden metadata
 * marker ({@code <!-- user-edited: YYYY-MM-DD -->}) so that future Dream runs
 * do not overwrite user modifications.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryHilService {

    /** Matches a whole-line user-edited marker so repeated edits do not accumulate markers. */
    private static final Pattern USER_EDITED_MARKER =
            Pattern.compile("(?m)^[ \\t]*<!-- user-edited:.*-->[ \\t]*\\r?\\n?");

    private final WorkspaceFileService workspaceFileService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Edit a section identified by key (section heading) inside {@code filename}.
     * Appends user-edited metadata so Dream prompts respect user changes.
     *
     * @param agentId    the agent whose workspace file is edited
     * @param filename   the target memory file (e.g. MEMORY.md / PROFILE.md / SOUL.md)
     * @param key        the section heading (text after {@code ## })
     * @param newContent the new section body
     */
    public void editMemoryEntry(Long agentId, String filename, String key, String newContent) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        String fileContent = (file != null && file.getContent() != null) ? file.getContent() : "";

        // Strip any pre-existing user-edited markers from the incoming body so a
        // section edited multiple times does not pick up a stack of markers.
        String cleanContent = USER_EDITED_MARKER.matcher(newContent).replaceAll("").trim();
        String metadata = "<!-- user-edited: " + LocalDate.now() + " -->";
        String sectionHeader = "## " + key;
        int headerIdx = fileContent.indexOf(sectionHeader);

        String updated;
        if (headerIdx < 0) {
            // Section not found — append as a new section.
            String newSection = sectionHeader + "\n" + cleanContent + "\n" + metadata;
            updated = fileContent.isBlank() ? newSection : fileContent.trim() + "\n\n" + newSection;
        } else {
            // Replace the existing section body, keeping the heading in place.
            int contentStart = fileContent.indexOf('\n', headerIdx) + 1;
            int nextSection = fileContent.indexOf("\n## ", contentStart);
            int sectionEnd = nextSection > 0 ? nextSection : fileContent.length();
            String replacement = cleanContent + "\n" + metadata + "\n";
            updated = fileContent.substring(0, contentStart) + replacement
                    + fileContent.substring(sectionEnd);
        }

        workspaceFileService.saveFile(agentId, filename, updated);
        // SOUL.md auto-evolution counts canonical memory writes. A manual SOUL.md
        // edit must not bump that counter, or a later auto-regeneration would
        // discard the user's edit; PROFILE.md likewise is not a write trigger.
        if ("MEMORY.md".equals(filename)) {
            eventPublisher.publishEvent(new MemoryWriteEvent(agentId, filename, "user-edit", cleanContent));
        }
        log.info("[HiL] User edited {} section '{}' for agent={}", filename, key, agentId);
    }

    /**
     * Check if a section heading exists in {@code filename}.
     * Used by DreamController to validate the edit key before allowing a write.
     */
    public boolean sectionExists(Long agentId, String filename, String key) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        if (file == null || file.getContent() == null) return false;
        return file.getContent().contains("## " + key);
    }
}
