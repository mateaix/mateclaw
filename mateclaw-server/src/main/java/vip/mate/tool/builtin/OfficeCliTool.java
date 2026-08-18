package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.ConcurrencyUnsafe;
import vip.mate.tool.document.FilenameSanitizer;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Optional structured adapter for iOfficeAI/OfficeCLI (issue #583).
 *
 * <p>The adapter deliberately exposes a narrow operation vocabulary instead of
 * accepting an arbitrary command line. Every input is copied into a private
 * scratch directory before OfficeCLI sees it, so even mutating operations are
 * copy-on-write and can never overwrite the user's source document. Generated
 * bytes are immediately moved into {@link GeneratedFileCache}; scratch files
 * are removed at the end of the call.</p>
 */
@Slf4j
@Component
public class OfficeCliTool {

    private static final Set<String> OFFICE_EXTENSIONS = Set.of("docx", "xlsx", "pptx");
    private static final Set<String> INSPECT_MODES = Set.of(
            "outline", "stats", "issues", "text", "annotated");
    private static final Map<String, String> RENDER_EXTENSIONS = Map.of(
            "html", "html",
            "screenshot", "png",
            "svg", "svg",
            "pdf", "pdf");
    private static final int DEFAULT_TIMEOUT_SECONDS = 90;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_BYTES = 50_000;
    private static final long MAX_INPUT_BYTES = 50L * 1024 * 1024;
    private static final long MAX_ARTIFACT_BYTES = 20L * 1024 * 1024;

    private final GeneratedFileCache generatedFileCache;
    private final ObjectMapper objectMapper;
    private final String executable;

    @Autowired
    public OfficeCliTool(GeneratedFileCache generatedFileCache, ObjectMapper objectMapper) {
        this(generatedFileCache, objectMapper, "officecli");
    }

    /** Test seam for a fake executable; production deliberately resolves from PATH. */
    OfficeCliTool(GeneratedFileCache generatedFileCache,
                  ObjectMapper objectMapper,
                  String executable) {
        this.generatedFileCache = generatedFileCache;
        this.objectMapper = objectMapper;
        this.executable = executable == null || executable.isBlank() ? "officecli" : executable.trim();
    }

    @ConcurrencyUnsafe("OfficeCLI starts native processes and may use document-level locks")
    @Tool(description = """
            Use the optional iOfficeAI/OfficeCLI engine to inspect, validate, batch-edit,
            merge, or render an existing .docx, .xlsx, or .pptx file. This complements
            the built-in Markdown renderers: use those for simple new files, and use this
            tool for existing templates, complex structure, validation, or visual QA.

            Actions:
            - inspect: read structure/content. mode is outline|stats|issues|text|annotated.
            - validate: run OpenXML validation.
            - batch: apply an OfficeCLI batch JSON array to a COPY of the input. payload is required.
            - merge: replace template placeholders using a JSON object. payload is required.
            - render: render to html|screenshot|svg|pdf. mode is required.

            Mutating actions NEVER overwrite the source. The result is returned as a
            generated-file download link. OfficeCLI must be installed on the MateClaw
            server; missing installations return a setup error.
            """)
    public String office_document(
            @ToolParam(description = "inspect | validate | batch | merge | render") String action,
            @ToolParam(description = "Workspace path or uploaded attachment name for a .docx/.xlsx/.pptx file") String filePath,
            @ToolParam(description = "Inspect/render mode; omitted for validate/batch/merge", required = false) String mode,
            @ToolParam(description = "JSON array for batch or JSON object for merge", required = false) String payload,
            @ToolParam(description = "Optional result filename; extension is normalized", required = false) String outputFilename,
            @ToolParam(description = "Timeout in seconds, default 90, maximum 300", required = false) Integer timeoutSeconds,
            @Nullable ToolContext ctx) {

        String normalizedAction = normalize(action);
        if (!Set.of("inspect", "validate", "batch", "merge", "render").contains(normalizedAction)) {
            return error("Unsupported action: " + action);
        }

        Path source;
        try {
            source = resolveInput(filePath, ctx);
        } catch (Exception e) {
            return error(e.getMessage());
        }

        String inputExtension = extension(source.getFileName().toString());
        if (!OFFICE_EXTENSIONS.contains(inputExtension)) {
            return error("OfficeCLI supports .docx, .xlsx, and .pptx inputs only");
        }
        if (!Files.isRegularFile(source)) {
            return error("Office input not found or not a regular file: " + filePath);
        }
        try {
            if (Files.size(source) > MAX_INPUT_BYTES) {
                return error("Office input exceeds the 50 MB limit");
            }
        } catch (IOException e) {
            return error("Cannot inspect Office input size: " + e.getMessage());
        }

        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);

        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("mc_officecli_");
            Path scratchInput = scratch.resolve("input." + inputExtension);
            Files.copy(source, scratchInput, StandardCopyOption.REPLACE_EXISTING);

            return switch (normalizedAction) {
                case "inspect" -> inspect(scratchInput, mode, timeout);
                case "validate" -> validate(scratchInput, timeout);
                case "batch" -> batch(scratchInput, inputExtension, payload, outputFilename, timeout, ctx);
                case "merge" -> merge(scratchInput, inputExtension, payload, outputFilename, timeout, ctx);
                case "render" -> render(scratchInput, mode, outputFilename, timeout, ctx);
                default -> error("Unsupported action: " + action);
            };
        } catch (Exception e) {
            log.warn("[OfficeCLI] action={} failed: {}", normalizedAction, e.getMessage());
            return error("OfficeCLI execution failed: " + e.getMessage());
        } finally {
            deleteTreeQuietly(scratch);
        }
    }

    private String inspect(Path input, String mode, int timeout) throws IOException, InterruptedException {
        String inspectMode = normalize(mode);
        if (!INSPECT_MODES.contains(inspectMode)) {
            return error("inspect mode must be one of: " + String.join(", ", INSPECT_MODES));
        }
        ProcessResult result = run(input.getParent(), timeout,
                List.of("view", input.toString(), inspectMode, "--json"));
        return processOnlyResult("inspect", result);
    }

    private String validate(Path input, int timeout) throws IOException, InterruptedException {
        ProcessResult result = run(input.getParent(), timeout,
                List.of("validate", input.toString(), "--json"));
        return processOnlyResult("validate", result);
    }

    private String batch(Path input, String extension, String payload, String outputFilename,
                         int timeout, @Nullable ToolContext ctx) throws IOException, InterruptedException {
        JsonNode commands = parsePayload(payload, true);
        if (commands == null) {
            return error("batch payload must be a non-empty JSON array");
        }
        String unsafeReason = validateBatchCommands(commands);
        if (unsafeReason != null) {
            return error(unsafeReason);
        }
        String displayName = outputName(outputFilename, "officecli-edited", extension);
        Path output = input.getParent().resolve("result." + extension);
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);

        ProcessResult result = run(input.getParent(), timeout,
                List.of("batch", output.toString(), "--commands", commands.toString(), "--json"));
        return generatedResult("batch", result, output, displayName, mimeFor(extension), ctx);
    }

    private String merge(Path input, String extension, String payload, String outputFilename,
                         int timeout, @Nullable ToolContext ctx) throws IOException, InterruptedException {
        JsonNode data = parsePayload(payload, false);
        if (data == null) {
            return error("merge payload must be a non-empty JSON object");
        }
        String displayName = outputName(outputFilename, "officecli-merged", extension);
        Path output = input.getParent().resolve("result." + extension);

        ProcessResult result = run(input.getParent(), timeout,
                List.of("merge", input.toString(), output.toString(), "--data", data.toString(), "--json"));
        return generatedResult("merge", result, output, displayName, mimeFor(extension), ctx);
    }

    private String render(Path input, String mode, String outputFilename,
                          int timeout, @Nullable ToolContext ctx) throws IOException, InterruptedException {
        String renderMode = normalize(mode);
        String extension = RENDER_EXTENSIONS.get(renderMode);
        if (extension == null) {
            return error("render mode must be one of: html, screenshot, svg, pdf");
        }
        String displayName = outputName(outputFilename, "officecli-preview", extension);
        Path output = input.getParent().resolve("rendered." + extension);

        // html/svg are streamed to stdout by OfficeCLI; screenshot/pdf accept -o.
        // Keep artifact bytes separate from the bounded diagnostic capture.
        ProcessResult result = Set.of("html", "svg").contains(renderMode)
                ? run(input.getParent(), timeout,
                        List.of("view", input.toString(), renderMode), output)
                : run(input.getParent(), timeout,
                        List.of("view", input.toString(), renderMode, "-o", output.toString()));
        return generatedResult("render", result, output, displayName, mimeFor(extension), ctx);
    }

    private JsonNode parsePayload(String payload, boolean array) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (array ? node.isArray() && !node.isEmpty() : node.isObject() && !node.isEmpty()) {
                return node;
            }
        } catch (Exception ignore) {
            // A concise validation error is returned by the caller.
        }
        return null;
    }

    private String generatedResult(String action, ProcessResult result, Path output,
                                   String displayName, String mime, @Nullable ToolContext ctx) throws IOException {
        if (result.exitCode() != 0 || result.timedOut()) {
            return processOnlyResult(action, result);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            return error("OfficeCLI completed without producing the expected output file");
        }
        if (Files.size(output) > MAX_ARTIFACT_BYTES) {
            return error("OfficeCLI output exceeds the 20 MB delivery limit");
        }
        String link = GeneratedFileLink.resultEn(
                Files.readAllBytes(output), displayName, mime, generatedFileCache, "Office file", 1, ctx);
        ObjectNode json = baseResult(action, result);
        json.put("generatedFile", link);
        return pretty(json);
    }

    private String processOnlyResult(String action, ProcessResult result) {
        return pretty(baseResult(action, result));
    }

    private ObjectNode baseResult(String action, ProcessResult result) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("success", result.exitCode() == 0 && !result.timedOut());
        json.put("action", action);
        json.put("exitCode", result.exitCode());
        json.put("stdout", result.stdout());
        json.put("stderr", result.stderr());
        json.put("timedOut", result.timedOut());
        if (result.setupMissing()) {
            json.put("setupRequired", true);
            json.put("message", "OfficeCLI is not installed on the MateClaw server PATH");
        }
        return json;
    }

    private ProcessResult run(Path workingDir, int timeoutSeconds, List<String> args)
            throws IOException, InterruptedException {
        return run(workingDir, timeoutSeconds, args, null);
    }

    private ProcessResult run(Path workingDir, int timeoutSeconds, List<String> args,
                              @Nullable Path stdoutArtifact)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        Path stdoutFile = stdoutArtifact == null
                ? Files.createTempFile(workingDir, "stdout-", ".log")
                : stdoutArtifact;
        Path stderrFile = Files.createTempFile(workingDir, "stderr-", ".log");
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            pb.environment().put("OFFICECLI_SKIP_UPDATE", "1");
            pb.environment().put("OFFICECLI_NO_AUTO_RESIDENT", "1");
            pb.environment().keySet().removeIf(key -> {
                String upper = key.toUpperCase(Locale.ROOT);
                return upper.contains("KEY") || upper.contains("SECRET") || upper.contains("TOKEN")
                        || upper.contains("PASSWORD") || upper.contains("CREDENTIAL");
            });

            try {
                process = pb.start();
            } catch (IOException e) {
                if (isMissingExecutable(e)) {
                    return new ProcessResult(-1, "", e.getMessage(), false, true);
                }
                throw e;
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
            }
            int exitCode = finished ? process.exitValue() : -1;
            return new ProcessResult(exitCode,
                    stdoutArtifact == null ? readTruncated(stdoutFile) : "",
                    readTruncated(stderrFile),
                    !finished,
                    false);
        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) killProcessTree(process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (stdoutArtifact == null) Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    private Path resolveInput(String filePath, @Nullable ToolContext ctx) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath is required");
        }
        try {
            Path path = WorkspacePathGuard.validatePath(filePath, ctx);
            if (Files.exists(path)) return path;
        } catch (IllegalArgumentException boundary) {
            Path attachment = ChatUploadResolver.resolve(filePath);
            if (attachment != null) return attachment;
            throw boundary;
        }
        Path attachment = ChatUploadResolver.resolve(filePath);
        if (attachment != null) return attachment;
        throw new IllegalArgumentException("Office input not found: " + filePath);
    }

    private String outputName(String requested, String fallback, String extension) {
        String base = FilenameSanitizer.sanitize(requested, fallback, "." + extension);
        return base + "." + extension;
    }

    /**
     * Keep the first-draft batch surface structural. Raw XML and importer verbs
     * can make OfficeCLI read arbitrary host files through relationship/media
     * properties even though the document itself lives in scratch space.
     */
    @Nullable
    private String validateBatchCommands(JsonNode commands) {
        Set<String> allowed = Set.of("add", "set", "remove", "move", "swap", "validate");
        for (JsonNode command : commands) {
            if (!command.isObject()) {
                return "Each batch item must be a JSON object";
            }
            String verb = normalize(command.path("command").asText(command.path("op").asText()));
            if (!allowed.contains(verb)) {
                return "Unsupported batch command in the safe adapter: " + verb;
            }
            String type = normalize(command.path("type").asText());
            if (Set.of("image", "picture", "video", "audio", "ole", "embeddedobject").contains(type)) {
                return "External media/OLE batch operations are not supported by the safe adapter";
            }
            String unsafeValue = findUnsafeHostPath(command);
            if (unsafeValue != null) {
                return "Batch payload contains a host filesystem reference that is not allowed: " + unsafeValue;
            }
        }
        return null;
    }

    @Nullable
    private String findUnsafeHostPath(JsonNode node) {
        if (node.isTextual()) {
            String value = node.asText().trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("file://") || lower.startsWith("~/") || lower.startsWith("~\\")
                    || value.matches("^[A-Za-z]:[\\\\/].*")) {
                return value;
            }
            if (value.matches(".*(^|[/\\\\])\\.\\.([/\\\\]|$).*")
                    || value.matches("^/(etc|var|tmp|home|users|root|opt|proc|sys|dev)(/.*)?$")) {
                return value;
            }
            return null;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                String unsafe = findUnsafeHostPath(child);
                if (unsafe != null) return unsafe;
            }
        }
        return null;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String mimeFor(String extension) {
        return switch (extension) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "html" -> "text/html";
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private static boolean isMissingExecutable(IOException e) {
        String message = e.getMessage();
        return message != null && (message.contains("No such file") || message.contains("CreateProcess error=2"));
    }

    private String readTruncated(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length <= MAX_OUTPUT_BYTES) return new String(bytes, StandardCharsets.UTF_8);
        return new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8)
                + "\n... [output truncated]";
    }

    private static void killProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            try { handle.destroyForcibly(); } catch (Exception ignore) { }
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteTreeQuietly(@Nullable Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignore) { }
            });
        } catch (IOException e) {
            log.debug("[OfficeCLI] failed to delete scratch directory {}: {}", root, e.getMessage());
        }
    }

    private String error(String message) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("success", false);
        json.put("error", message == null ? "Unknown OfficeCLI error" : message);
        return pretty(json);
    }

    private String pretty(JsonNode json) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return json.toString();
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut, boolean setupMissing) { }
}
