package vip.mate.team.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.net.URI;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Builds the canonical delivery projection from existing run and task records. */
final class TeamRunViewFactory {

    private static final String GENERATED_FILE_PATH = "/api/v1/files/generated/";
    private static final Duration STALLED_WINDOW = Duration.ofMinutes(15);
    private static final int SUMMARY_LIMIT = 500;
    private static final Set<String> OUTCOME_QUALITIES = Set.of("synthesized", "fallback", "partial", "pending");

    private TeamRunViewFactory() {
    }

    static TeamRunView create(TeamRunEntity run, String status, TeamRunView.Progress progress,
                              List<TeamTaskEntity> tasks, boolean includeTasks) {
        List<TeamRunView.Deliverable> deliverables = deliverables(run, tasks);
        LocalDateTime lastActivity = lastActivity(run, tasks);
        return new TeamRunView(run.getId(), run.getTeamId(), run.getWorkspaceId(), run.getLeadAgentId(),
                run.getLeadConversationId(), run.getOriginMessageId(), run.getTitle(), run.getObjective(),
                status, run.getFinalSummary(), run.getStopReason(), run.getMetadata(), run.getStartedAt(),
                run.getCompletedAt(), run.getCreateTime(), run.getUpdateTime(),
                includeTasks ? "full" : "summary", outcomeQuality(run, tasks),
                deliverables, contributions(tasks), attentionItems(run, tasks),
                liveness(status, lastActivity, tasks),
                metrics(run, tasks, deliverables.size()), progress,
                tasks.stream().map(includeTasks ? TeamRunView.Task::from : TeamRunView.Task::summaryFrom)
                        .toList());
    }

    private static String outcomeQuality(TeamRunEntity run, List<TeamTaskEntity> tasks) {
        if (run.getFinalSummary() != null && !run.getFinalSummary().isBlank()) {
            String projected = metadata(run.getMetadata()).getStr("summaryQuality");
            return projected != null && OUTCOME_QUALITIES.contains(projected) ? projected : "synthesized";
        }
        if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !TeamTaskStatus.isTerminal(task.getStatus()))) {
            return "pending";
        }
        boolean allCompleted = tasks.stream()
                .allMatch(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()));
        return allCompleted ? "fallback" : "partial";
    }

    /**
     * Aggregates run-level and task-level metadata in that order. Entries are
     * de-duplicated by safe normalized URL. Display fields use the first
     * non-empty value, missing timestamps are filled by later duplicates, and
     * verification status may only move to a stronger (non-degraded) state.
     * Explicit source arrays and task-implied sources are merged in order.
     */
    private static List<TeamRunView.Deliverable> deliverables(TeamRunEntity run,
                                                              List<TeamTaskEntity> tasks) {
        Map<String, MutableDeliverable> unique = new LinkedHashMap<>();
        collectDeliverables(unique, run.getMetadata(), null, null);
        for (TeamTaskEntity task : tasks) {
            collectDeliverables(unique, task.getMetadata(), task.getId(), task.getAssigneeAgentId());
        }
        return unique.values().stream().map(value -> new TeamRunView.Deliverable(value.id,
                value.name == null ? value.url : value.name, value.url,
                value.type == null ? fileType(value.url) : value.type,
                List.copyOf(value.taskIds), List.copyOf(value.agentIds), value.createdAt,
                value.verificationStatus == null ? "available" : value.verificationStatus)).toList();
    }

    private static void collectDeliverables(Map<String, MutableDeliverable> unique, String rawMetadata,
                                            Long taskId, Long agentId) {
        JSONArray values = metadata(rawMetadata).getJSONArray("deliverables");
        if (values == null) {
            return;
        }
        for (Object value : values) {
            if (!(value instanceof JSONObject item)) {
                continue;
            }
            String name = text(item.getStr("name"));
            SafeUrl url = normalizeDeliverableUrl(text(item.getStr("url")));
            if (url == null) {
                continue;
            }
            MutableDeliverable delivery = unique.computeIfAbsent(url.identity(), ignored -> new MutableDeliverable(
                    stableId(url.identity()), name, url.href(), text(item.getStr("type")),
                    deliverableTime(item), verificationStatus(item)));
            if (delivery.name == null) delivery.name = name;
            if (delivery.type == null) delivery.type = text(item.getStr("type"));
            if (delivery.createdAt == null) delivery.createdAt = deliverableTime(item);
            String candidateStatus = verificationStatus(item);
            if (verificationRank(candidateStatus) > verificationRank(delivery.verificationStatus)) {
                delivery.verificationStatus = candidateStatus;
            }
            addIds(delivery.taskIds, item.getJSONArray("sourceTaskIds"));
            addIds(delivery.agentIds, item.getJSONArray("sourceAgentIds"));
            add(delivery.taskIds, taskId);
            add(delivery.agentIds, agentId);
        }
    }

    private static LocalDateTime deliverableTime(JSONObject item) {
        LocalDateTime createdAt = parseTime(item.getStr("createdAt"));
        return createdAt != null ? createdAt : parseTime(item.getStr("time"));
    }

    private static String verificationStatus(JSONObject item) {
        String status = text(item.getStr("verificationStatus"));
        if (status == null) {
            return null;
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "verified", "available", "pending", "failed", "unavailable", "rejected" -> normalized;
            default -> null;
        };
    }

    private static int verificationRank(String status) {
        if (status == null) return -1;
        return switch (status) {
            case "verified" -> 4;
            case "available" -> 3;
            case "pending" -> 2;
            case "failed", "unavailable", "rejected" -> 1;
            default -> 0;
        };
    }

    private static void addIds(LinkedHashSet<Long> target, JSONArray values) {
        if (values == null) {
            return;
        }
        for (Object value : values) {
            try {
                Long id = null;
                if (value instanceof Number number) {
                    id = new BigDecimal(number.toString()).longValueExact();
                } else if (value instanceof String string && !string.isBlank()) {
                    id = Long.parseLong(string);
                }
                add(target, id);
            } catch (ArithmeticException | NumberFormatException ignored) {
                // One malformed source id must not discard the deliverable.
            }
        }
    }

    private static List<TeamRunView.MemberContribution> contributions(List<TeamTaskEntity> tasks) {
        return tasks.stream().map(task -> new TeamRunView.MemberContribution(task.getId(),
                task.getAssigneeAgentId(), task.getSubject(), task.getStatus(),
                durationSeconds(task.getCreateTime(), task.getUpdateTime()), task.getUpdateTime(),
                summarize(task.getResult()), task.getConversationId())).toList();
    }

    private static List<TeamRunView.AttentionItem> attentionItems(TeamRunEntity run,
                                                                  List<TeamTaskEntity> tasks) {
        List<TeamRunView.AttentionItem> items = new ArrayList<>();
        for (TeamTaskEntity task : tasks) {
            String type = switch (task.getStatus()) {
                case TeamTaskStatus.IN_REVIEW -> "review";
                case TeamTaskStatus.FAILED -> "failure";
                case TeamTaskStatus.BLOCKED -> "blocked";
                case TeamTaskStatus.STALE -> "stale";
                default -> null;
            };
            if (type != null) {
                String message = text(task.getReason());
                int priority = TeamTaskStatus.IN_REVIEW.equals(task.getStatus()) ? 0 : 20;
                items.add(new TeamRunView.AttentionItem("task:" + task.getId() + ":" + type,
                        type, priority == 0 ? "action" : "error", priority,
                        task.getId(), message == null ? task.getSubject() : message, task.getUpdateTime()));
            }
        }
        String quality = outcomeQuality(run, tasks);
        if ("fallback".equals(quality) || "partial".equals(quality)) {
            items.add(new TeamRunView.AttentionItem("run:" + run.getId() + ":synthesis", "synthesis",
                    "warning", 10, null, "Final synthesis used a degraded outcome", run.getUpdateTime()));
        }
        if (text(run.getStopReason()) != null) {
            items.add(new TeamRunView.AttentionItem("run:" + run.getId() + ":stopped", "stopped",
                    "warning", 10, null, run.getStopReason(), run.getUpdateTime()));
        }
        items.sort((left, right) -> {
            int priority = Integer.compare(left.priority(), right.priority());
            return priority != 0 ? priority : compareNullableDesc(left.createdAt(), right.createdAt());
        });
        return List.copyOf(items);
    }

    private static TeamRunView.Liveness liveness(String status, LocalDateTime lastActivity,
                                                 List<TeamTaskEntity> tasks) {
        if (TeamRunStatus.isTerminal(status)) {
            return new TeamRunView.Liveness("terminal", lastActivity);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean leased = tasks.stream().anyMatch(task -> TeamTaskStatus.IN_PROGRESS.equals(task.getStatus())
                && task.getLockExpiresAt() != null && task.getLockExpiresAt().isAfter(now));
        if (leased) {
            return new TeamRunView.Liveness("live", lastActivity);
        }
        if (lastActivity == null) {
            return new TeamRunView.Liveness("quiet", null);
        }
        Duration age = Duration.between(lastActivity, now);
        String state = age.compareTo(STALLED_WINDOW) <= 0 ? "quiet" : "stalled";
        return new TeamRunView.Liveness(state, lastActivity);
    }

    private static TeamRunView.Metrics metrics(TeamRunEntity run, List<TeamTaskEntity> tasks,
                                               int deliverableCount) {
        int completed = (int) tasks.stream()
                .filter(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus())).count();
        int failed = (int) tasks.stream()
                .filter(task -> TeamTaskStatus.FAILED.equals(task.getStatus())).count();
        LocalDateTime end = run.getCompletedAt() != null ? run.getCompletedAt() : lastActivity(run, tasks);
        return new TeamRunView.Metrics(durationSeconds(run.getStartedAt(), end), tasks.size(), completed,
                failed, deliverableCount);
    }

    private static LocalDateTime lastActivity(TeamRunEntity run, List<TeamTaskEntity> tasks) {
        LocalDateTime latest = max(run.getUpdateTime(), run.getCompletedAt(), run.getStartedAt(),
                run.getCreateTime());
        for (TeamTaskEntity task : tasks) {
            latest = max(latest, task.getUpdateTime(), task.getCreateTime());
        }
        return latest;
    }

    private static LocalDateTime max(LocalDateTime... values) {
        LocalDateTime latest = null;
        for (LocalDateTime value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    private static Long durationSeconds(LocalDateTime start, LocalDateTime end) {
        return start == null || end == null || end.isBefore(start) ? null : Duration.between(start, end).toSeconds();
    }

    private static SafeUrl normalizeDeliverableUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null || uri.getRawAuthority() != null) {
                return null;
            }
            String rawPath = uri.getRawPath();
            if (rawPath == null || rawPath.indexOf('\\') >= 0) {
                return null;
            }
            String path = fullyDecode(rawPath);
            if (path == null || path.indexOf('\\') >= 0) {
                return null;
            }
            Path parsed = Path.of(path);
            for (Path segment : parsed) {
                if ("..".equals(segment.toString())) {
                    return null;
                }
            }
            String normalized = parsed.normalize().toString();
            return normalized.startsWith(GENERATED_FILE_PATH) ? new SafeUrl(normalized, rawPath) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String fullyDecode(String path) {
        String decoded = path;
        for (int remaining = path.length() + 1; remaining > 0; remaining--) {
            String next = decodePercentOnce(decoded);
            if (next.equals(decoded)) {
                return decoded;
            }
            decoded = next;
        }
        return null;
    }

    private static String decodePercentOnce(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '%') {
                decoded.append(value.charAt(index++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < value.length() && value.charAt(index) == '%') {
                if (index + 2 >= value.length()) {
                    throw new IllegalArgumentException("Incomplete percent escape");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent escape");
                }
                bytes.write((high << 4) | low);
                index += 3;
            }
            decoded.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    private static String stableId(String url) {
        return UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String fileType(String url) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException invalid) {
            path = url;
        }
        int dot = path == null ? -1 : path.lastIndexOf('.');
        return dot < 0 ? "file" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static LocalDateTime parseTime(String value) {
        try {
            return value == null ? null : LocalDateTime.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static JSONObject metadata(String value) {
        try {
            return value == null || value.isBlank() ? new JSONObject() : JSONUtil.parseObj(value);
        } catch (RuntimeException invalid) {
            return new JSONObject();
        }
    }

    private static String summarize(String value) {
        String normalized = text(value);
        return normalized == null || normalized.length() <= SUMMARY_LIMIT
                ? normalized : normalized.substring(0, SUMMARY_LIMIT);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> void add(LinkedHashSet<T> values, T value) {
        if (value instanceof Long id ? id > 0 : value != null) {
            values.add(value);
        }
    }

    private static int compareNullableDesc(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    private static final class MutableDeliverable {
        private final String id;
        private String name;
        private final String url;
        private String type;
        private LocalDateTime createdAt;
        private String verificationStatus;
        private final LinkedHashSet<Long> taskIds = new LinkedHashSet<>();
        private final LinkedHashSet<Long> agentIds = new LinkedHashSet<>();

        private MutableDeliverable(String id, String name, String url, String type,
                                   LocalDateTime createdAt, String verificationStatus) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.type = type;
            this.createdAt = createdAt;
            this.verificationStatus = verificationStatus;
        }
    }

    private record SafeUrl(String identity, String href) {
    }
}
