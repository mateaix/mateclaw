package vip.mate.skill.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import vip.mate.skill.model.SkillEntity;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structured result of one lifecycle sweep — serialized to {@code run.json}
 * and rendered to {@code REPORT.md}. Built incrementally during the sweep
 * via {@link Builder}.
 *
 * <p>{@code planned} counts reflect what {@code planTransition} decided and
 * are populated in both dry-run and applied modes. {@code applied} counts
 * reflect what actually committed and stay zero for a dry-run.
 *
 * @author MateClaw Team
 */
@Getter
public class SkillCuratorReport {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String runId;
    private final LocalDateTime runAt;
    private final boolean dryRun;
    private final Config config;
    private final int scanned;
    private final Counts planned;
    private final Counts applied;
    private final List<TransitionRow> transitions;
    private final List<BlockedByBindingRow> blockedByBindings;
    private final List<String> reconciliations;

    /** Set by the report store after the run directory is written. */
    @JsonIgnore
    private Path path;

    private SkillCuratorReport(Builder b) {
        this.runAt = b.runAt != null ? b.runAt : LocalDateTime.now();
        this.runId = this.runAt.format(RUN_ID);
        this.dryRun = b.dryRun;
        this.config = new Config(b.staleAfterDays, b.archiveAfterDays, b.scope);
        this.scanned = b.scanned;
        this.planned = new Counts(b.plannedStale, b.plannedArchived, b.plannedReactivated);
        this.applied = new Counts(b.appliedStale, b.appliedArchived, b.appliedReactivated);
        this.transitions = List.copyOf(b.transitions);
        this.blockedByBindings = List.copyOf(b.blockedByBindings);
        this.reconciliations = List.copyOf(b.reconciliations);
    }

    public void setPath(Path path) {
        this.path = path;
    }

    /** Applied count of skills marked stale (0 for a dry-run). */
    public int markedStale() {
        return applied.stale();
    }

    /** Applied count of skills archived (0 for a dry-run). */
    public int archived() {
        return applied.archived();
    }

    /** Applied count of skills reactivated (0 for a dry-run). */
    public int reactivated() {
        return applied.reactivated();
    }

    public record Config(int staleAfterDays, int archiveAfterDays, String scope) {}

    public record Counts(int stale, int archived, int reactivated) {}

    public record TransitionRow(Long skillId, String name, String from, String to, long daysIdle) {}

    public static Builder builder() {
        return new Builder();
    }

    /** Incremental builder used by the sweep. */
    public static final class Builder {
        private LocalDateTime runAt;
        private boolean dryRun;
        private int staleAfterDays;
        private int archiveAfterDays;
        private String scope;
        private int scanned;
        private int plannedStale, plannedArchived, plannedReactivated;
        private int appliedStale, appliedArchived, appliedReactivated;
        private final List<TransitionRow> transitions = new ArrayList<>();
        private List<BlockedByBindingRow> blockedByBindings = new ArrayList<>();
        private final List<String> reconciliations = new ArrayList<>();

        public Builder runAt(LocalDateTime runAt) {
            this.runAt = runAt;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder config(int staleAfterDays, int archiveAfterDays, String scope) {
            this.staleAfterDays = staleAfterDays;
            this.archiveAfterDays = archiveAfterDays;
            this.scope = scope;
            return this;
        }

        public Builder scanned(int scanned) {
            this.scanned = scanned;
            return this;
        }

        /** Record a non-NONE transition for a skill in the {@code transitions} list. */
        public Builder add(SkillEntity skill, LifecycleTransition t) {
            if (t == null || t == LifecycleTransition.NONE) {
                return this;
            }
            LocalDateTime anchor = skill.getLastActivityAt() != null
                    ? skill.getLastActivityAt() : skill.getCreateTime();
            long days = anchor == null || runAt == null ? 0L : Duration.between(anchor, runAt).toDays();
            String from = Optional.ofNullable(skill.getLifecycleState()).orElse("active");
            String to = switch (t) {
                case TO_STALE -> "stale";
                case TO_ARCHIVED -> "archived";
                case REACTIVATE -> "active";
                case NONE -> from;
            };
            transitions.add(new TransitionRow(skill.getId(), skill.getName(), from, to, days));
            return this;
        }

        public Builder plannedCounts(int stale, int archived, int reactivated) {
            this.plannedStale = stale;
            this.plannedArchived = archived;
            this.plannedReactivated = reactivated;
            return this;
        }

        public Builder appliedCounts(int stale, int archived, int reactivated) {
            this.appliedStale = stale;
            this.appliedArchived = archived;
            this.appliedReactivated = reactivated;
            return this;
        }

        public Builder blockedByBindings(List<BlockedByBindingRow> rows) {
            this.blockedByBindings = rows != null ? rows : new ArrayList<>();
            return this;
        }

        public Builder reconciliation(String message) {
            if (message != null && !message.isBlank()) {
                this.reconciliations.add(message);
            }
            return this;
        }

        public SkillCuratorReport build() {
            return new SkillCuratorReport(this);
        }
    }
}
