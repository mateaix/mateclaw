package vip.mate.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;
import vip.mate.tool.document.GeneratedFileCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Fixed platform IO/recipe replay. Does not call HTTP, a model, a shell or an Agent. */
final class OfflineJsonArtifactTaskReplay {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    enum Operation { CHECK, REWRITE_DISK, TOO_SMALL_BUDGET, FOREIGN_OWNER }
    enum Status { MATCH, MISSING_FIELDS, INVALID_JSON, UNKNOWN, STALE, UNAVAILABLE }
    record Suite(Integer schemaVersion, String suiteId, List<Task> tasks) { }
    record Task(String id, String task, String source, Operation operation, String content,
                List<String> requiredFields, Expected expected) { }
    record Expected(Status status, List<String> missingFields, Boolean recipeInvoked, Boolean acceptanceEligible) { }
    record Actual(String readStatus, Status status, List<String> missingFields, boolean recipeInvoked,
                  String recipeId, int recipeRevision, boolean acceptanceEligible) { }
    record Case(String id, String task, String source, Expected expected, Actual actual, boolean matched, String error) { }
    record Report(int schemaVersion, String suiteId, String suiteSha256, String revisionLabel,
                  Map<String, String> productionClassSha256, String executionMode, int onlineModelCalls,
                  String agentTaskSuccessRate, String onlineCost, int matchedCases, int mismatchedCases, List<Case> cases) { }

    static Suite parse(byte[] bytes) throws IOException {
        Suite suite = JSON.readValue(bytes, Suite.class);
        require(suite != null && Integer.valueOf(1).equals(suite.schemaVersion()), "schemaVersion must be 1");
        require(nonblank(suite.suiteId()) && suite.tasks() != null && !suite.tasks().isEmpty()
                && suite.tasks().size() <= 100, "suiteId and 1..100 tasks required");
        Set<String> ids = new HashSet<>();
        for (Task task : suite.tasks()) {
            require(task != null && nonblank(task.id()) && ids.add(task.id()), "unique IDs required");
            require(nonblank(task.task()) && nonblank(task.source()) && task.operation() != null
                    && task.content() != null && task.content().length() <= 16_384, "invalid task inputs");
            JsonArtifactRecipe.validate(task.requiredFields());
            Expected e = task.expected();
            require(e != null && e.status() != null && e.missingFields() != null
                    && e.recipeInvoked() != null && e.acceptanceEligible() != null, "all expectations required");
            require(e.missingFields().stream().allMatch(task.requiredFields()::contains), "unknown missing field expectation");
        }
        return suite;
    }

    static Report run(byte[] bytes, Path root, String revisionLabel) throws IOException {
        Suite suite = parse(bytes); // Validate the entire suite before any file mutation.
        require(nonblank(revisionLabel), "revisionLabel required");
        List<Case> results = new ArrayList<>();
        for (Task task : suite.tasks()) {
            try {
                Actual actual = execute(task, Files.createTempDirectory(root, "json-artifact-case-"));
                Expected e = task.expected();
                boolean matched = e.status() == actual.status() && e.missingFields().equals(actual.missingFields())
                        && e.recipeInvoked() == actual.recipeInvoked() && e.acceptanceEligible() == actual.acceptanceEligible();
                results.add(new Case(task.id(), task.task(), task.source(), e, actual, matched, null));
            } catch (IOException | RuntimeException error) {
                results.add(new Case(task.id(), task.task(), task.source(), task.expected(), null, false,
                        error.getClass().getSimpleName()));
            }
        }
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(GeneratedFileCache.class, GeneratedFileCache.ArtifactRead.class,
                JsonArtifactRecipe.class, JsonArtifactRecipe.Result.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("Missing tested class " + type.getName());
                classes.put(type.getName(), digest(stream.readAllBytes()));
            }
        }
        int matched = (int) results.stream().filter(Case::matched).count();
        return new Report(1, suite.suiteId(), digest(bytes), revisionLabel, classes,
                "offline_platform_fixture_jsoncheck", 0, "not_measured", "not_measured", matched,
                results.size() - matched, List.copyOf(results));
    }

    private static Actual execute(Task task, Path root) throws IOException {
        var cache = new GeneratedFileCache(root);
        byte[] bytes = task.content().getBytes(StandardCharsets.UTF_8);
        String id = cache.put(bytes, "report.json", "application/json", new GeneratedFileCache.Owner(1L, 1L, "fixture"));
        if (task.operation() == Operation.REWRITE_DISK) Files.writeString(root.resolve(id), "{}");
        int budget = task.operation() == Operation.TOO_SMALL_BUDGET ? 1 : 1_048_576;
        long owner = task.operation() == Operation.FOREIGN_OWNER ? 2L : 1L;
        var read = cache.readDurableArtifactSnapshot(id, owner, "fixture", digest(bytes), budget);
        boolean invoked = "READ".equals(read.status());
        var result = invoked ? JsonArtifactRecipe.check(read.bytes(), task.requiredFields())
                : JsonArtifactRecipe.outcome(read.status(), task.requiredFields(), List.of());
        return new Actual(read.status(), Status.valueOf(result.status()), result.missingFields(), invoked,
                result.recipeId(), result.recipeRevision(), result.acceptanceEligible());
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
