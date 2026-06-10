package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Persists lifecycle sweep reports to {@code {workspace-root}/.curator/}.
 * Each run gets a {@code {runId}/} directory holding {@code run.json} (the
 * structured record) and {@code REPORT.md} (a human-readable render); a
 * {@code latest} symlink points at the newest run.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCuratorReportStore {

    /** Number of run directories kept on disk; older ones are pruned. */
    private static final int KEEP_RUNS = 50;

    /** Run ids are {@code yyyyMMdd-HHmmss} — validated before any path resolve. */
    private static final Pattern RUN_ID = Pattern.compile("\\d{8}-\\d{6}");

    private final SkillWorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper;

    private Path curatorRoot() {
        return workspaceManager.getWorkspaceRoot().resolve(".curator");
    }

    /**
     * Write the report's run directory and update the {@code latest}
     * symlink. The report's {@code path} is populated on success.
     */
    public SkillCuratorReport write(SkillCuratorReport report) {
        Path runDir = curatorRoot().resolve(report.getRunId());
        try {
            Files.createDirectories(runDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(runDir.resolve("run.json").toFile(), report);
            Files.writeString(runDir.resolve("REPORT.md"), renderMarkdown(report));
            report.setPath(runDir);
            updateLatest(runDir);
            pruneOld();
        } catch (IOException e) {
            log.warn("Failed to write curator report {}: {}", report.getRunId(), e.getMessage());
        }
        return report;
    }

    /** Most recent run ids, newest first, capped at {@code limit}. */
    public List<String> listRunIds(int limit) {
        Path root = curatorRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> RUN_ID.matcher(n).matches())
                    .sorted(Comparator.reverseOrder())
                    .limit(limit > 0 ? limit : 20)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list curator reports: {}", e.getMessage());
            return List.of();
        }
    }

    /** Newest run id, or {@code null} when no run has been recorded yet. */
    public String latestRunId() {
        List<String> ids = listRunIds(1);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Parsed {@code run.json} for a run, or {@code null} when the run is
     * unknown. The {@code runId} is validated against the timestamp pattern
     * before being resolved as a path component.
     */
    public Object readRun(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            return null;
        }
        Path runJson = curatorRoot().resolve(runId).resolve("run.json");
        if (!Files.isRegularFile(runJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(runJson.toFile(), Object.class);
        } catch (IOException e) {
            log.warn("Failed to read curator report {}: {}", runId, e.getMessage());
            return null;
        }
    }

    private void updateLatest(Path runDir) {
        Path latest = curatorRoot().resolve("latest");
        try {
            Files.deleteIfExists(latest);
            Files.createSymbolicLink(latest, runDir.getFileName());
        } catch (IOException | UnsupportedOperationException e) {
            // Symlinks may be unsupported (Windows without privilege) — the
            // latest run is still discoverable via listRunIds().
            log.debug("Curator 'latest' symlink not updated: {}", e.getMessage());
        }
    }

    private void pruneOld() {
        List<String> ids = listRunIds(Integer.MAX_VALUE);
        if (ids.size() <= KEEP_RUNS) {
            return;
        }
        for (String old : ids.subList(KEEP_RUNS, ids.size())) {
            deleteRecursively(curatorRoot().resolve(old));
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best-effort prune */
                }
            });
        } catch (IOException e) {
            log.debug("Failed to prune curator report {}: {}", dir, e.getMessage());
        }
    }

    private String renderMarkdown(SkillCuratorReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill Curator Run ").append(r.getRunId()).append("\n\n");
        sb.append("- Run at: ").append(r.getRunAt()).append('\n');
        sb.append("- Mode: ").append(r.isDryRun() ? "dry-run (preview)" : "applied").append('\n');
        sb.append("- Scope: ").append(r.getConfig().scope())
                .append(" (stale ≥ ").append(r.getConfig().staleAfterDays())
                .append("d, archive ≥ ").append(r.getConfig().archiveAfterDays()).append("d)\n");
        sb.append("- Scanned: ").append(r.getScanned()).append(" candidate(s)\n\n");

        sb.append("## Planned\n\n");
        sb.append("| stale | archived | reactivated |\n|---|---|---|\n");
        sb.append("| ").append(r.getPlanned().stale())
                .append(" | ").append(r.getPlanned().archived())
                .append(" | ").append(r.getPlanned().reactivated()).append(" |\n\n");

        sb.append("## Applied\n\n");
        sb.append("| stale | archived | reactivated |\n|---|---|---|\n");
        sb.append("| ").append(r.getApplied().stale())
                .append(" | ").append(r.getApplied().archived())
                .append(" | ").append(r.getApplied().reactivated()).append(" |\n\n");

        if (!r.getTransitions().isEmpty()) {
            sb.append("## Transitions\n\n");
            sb.append("| skill | from | to | days idle |\n|---|---|---|---|\n");
            for (SkillCuratorReport.TransitionRow t : r.getTransitions()) {
                sb.append("| ").append(t.name()).append(" (").append(t.skillId()).append(')')
                        .append(" | ").append(t.from())
                        .append(" | ").append(t.to())
                        .append(" | ").append(t.daysIdle()).append(" |\n");
            }
            sb.append('\n');
        }

        if (!r.getBlockedByBindings().isEmpty()) {
            sb.append("## Blocked by agent bindings\n\n");
            sb.append("These skills satisfy the idle window but are kept because an "
                    + "enabled agent explicitly binds them.\n\n");
            sb.append("| skill | bound agents | days idle |\n|---|---|---|\n");
            for (BlockedByBindingRow b : r.getBlockedByBindings()) {
                sb.append("| ").append(b.name()).append(" (").append(b.skillId()).append(')')
                        .append(" | ").append(b.agentIds())
                        .append(" | ").append(b.daysIdle()).append(" |\n");
            }
            sb.append('\n');
        }

        if (!r.getReconciliations().isEmpty()) {
            sb.append("## Reconciliations\n\n");
            for (String line : r.getReconciliations()) {
                sb.append("- ").append(line).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
