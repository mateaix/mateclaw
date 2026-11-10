package vip.mate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.channel.web.AgentStreamAccumulator;
import vip.mate.channel.web.ChatController;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.config.GraphObservationProperties;
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.disclosure.ToolDisclosureService;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.service.ToolGuardService;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeAttachmentRecoveryIntegrationTest {
    private static final String CONVERSATION = "native-attachment-recovery";
    private static final String TOOL = "extract_document_text";
    private static final String ANSWER = "Excel total: 149. Word approval: KESTREL-683-VIOLET.";
    private static final String VERDICT = """
            {"decision":"ACCEPT","taskComplete":true,"remainingWork":false,
             "failedCallCount":0,"recoveryClaims":[],"reason":"Both document results support the answer"}
            """;

    @TempDir Path workspace;

    @Test
    void parallelDocumentResultsSurviveTruncationAndAppearOnceLiveAndOnReconnect() throws Exception {
        writeDocuments();
        var mapper = new ObjectMapper();
        var tracker = new ChatStreamTracker(mapper);
        try {
            var extractions = new CopyOnWriteArrayList<String>();
            var parser = new DocumentExtractTool();
            // Exercise Spring's generated schema on the real AgentScope toolkit classpath.
            var springDefinition = ToolCallbacks.from(parser)[0].getToolDefinition();
            var tool = new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() {
                    return springDefinition;
                }

                @Override public String call(String arguments) {
                    try {
                        String file = mapper.readTree(arguments).path("filePath").asText();
                        assertTrue(Set.of("shipments.xlsx", "approval.docx").contains(file));
                        extractions.add(file);
                        return parser.extractTrustedDocument(workspace.resolve(file).toString(),
                                "{\"method\":\"tika\"}");
                    } catch (java.io.IOException failure) {
                        throw new IllegalArgumentException(failure);
                    }
                }
            };
            var tools = AgentToolSet.fromCallbacks(List.of(), List.of(tool));
            var model = mock(ChatModel.class);
            var calls = new AtomicInteger();
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                return switch (calls.incrementAndGet()) {
                    case 1 -> Flux.just(new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().toolCalls(List.of(
                                    new AssistantMessage.ToolCall("xlsx", "function", TOOL,
                                            "{\"filePath\":\"shipments.xlsx\"}"),
                                    new AssistantMessage.ToolCall("docx", "function", TOOL,
                                            "{\"filePath\":\"approval.docx\"}"))).build()))));
                    case 2 -> Flux.just(response("truncated draft", "length"));
                    case 3 -> {
                        String contents = prompt.getContents();
                        assertTrue(contents.contains("Write a concise complete replacement"));
                        assertTrue(contents.contains("149"));
                        assertTrue(contents.contains("KESTREL-683-VIOLET"));
                        assertFalse(contents.contains("truncated draft"));
                        yield Flux.just(response(ANSWER, "stop"));
                    }
                    case 4 -> {
                        assertTrue(prompt.getContents().contains(ANSWER));
                        yield Flux.just(response(VERDICT, "stop"));
                    }
                    default -> throw new AssertionError("Unexpected model call");
                };
            });
            var graph = builder(tracker, tool).buildReActGraph(tools, model, 10, null);
            var agent = new StateGraphReActAgent(mock(ChatClient.class),
                    mock(ConversationService.class), graph, model, null, tools);
            var accumulator = accumulator(mapper, tracker);

            tracker.register(CONVERSATION);
            var live = new CapturingEmitter();
            tracker.attach(CONVERSATION, live);
            agent.chatStructuredStream("Read both attachments and report the total and approval code.", CONVERSATION)
                    .doOnNext(delta -> accumulator.accept(delta, CONVERSATION)).blockLast(Duration.ofSeconds(30));

            assertEquals(ANSWER, accumulator.getContent());
            assertEquals("normal", accumulator.getFinishReason());
            assertEquals(4, calls.get());
            assertEquals(List.of("approval.docx", "shipments.xlsx"), extractions.stream().sorted().toList());
            var metadata = mapper.readTree(accumulator.toMetadataJson());
            assertEquals(2, metadata.path("toolCalls").size());
            assertTrue(metadata.path("toolCalls").get(0).path("success").asBoolean());
            assertTrue(metadata.path("toolCalls").get(1).path("success").asBoolean());
            var reconnected = new CapturingEmitter();
            tracker.attach(CONVERSATION, reconnected);
            for (var emitter : List.of(live, reconnected)) {
                assertEquals(2, emitter.count("tool_call_started"));
                assertEquals(2, emitter.count("tool_call_completed"));
                assertFalse(emitter.wire().contains("truncated draft"));
            }
        } finally {
            tracker.onShutdown();
        }
    }

    @Test
    void approvalReplayCanPublishAnotherOccurrenceOfTheSameCall() {
        var mapper = new ObjectMapper();
        var tracker = new ChatStreamTracker(mapper);
        try {
            var accumulator = accumulator(mapper, tracker);
            tracker.register(CONVERSATION);
            var live = new CapturingEmitter();
            tracker.attach(CONVERSATION, live);

            String type = GraphEventPublisher.EVENT_TOOL_START;
            var original = Map.<String, Object>of(
                    "toolCallId", "approved", "toolName", TOOL, "timestamp", 1L);
            var replay = Map.<String, Object>of(
                    "toolCallId", "approved", "toolName", TOOL, "timestamp", 2L);
            tracker.broadcastLiveToolEvent(CONVERSATION, new GraphEventPublisher.GraphEvent(type, replay, 2L));
            accumulator.accept(AgentService.StreamDelta.event(type, original), CONVERSATION);
            accumulator.accept(AgentService.StreamDelta.event(type, replay), CONVERSATION);
            assertEquals(2, live.count(type));
        } finally {
            tracker.onShutdown();
        }
    }

    private static AgentStreamAccumulator accumulator(ObjectMapper mapper, ChatStreamTracker tracker) {
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "streamTracker", tracker);
        return ReflectionTestUtils.invokeMethod(controller, "newAccumulator");
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    private static AgentGraphBuilder builder(ChatStreamTracker tracker, ToolCallback tool) {
        var builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
        for (var field : AgentGraphBuilder.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                ReflectionTestUtils.setField(builder, field.getName(), mock(field.getType()));
            }
        }
        ReflectionTestUtils.setField(builder, "streamTracker", tracker);
        ReflectionTestUtils.setField(builder, "providerHealthTracker",
                new ProviderHealthTracker(new ProviderHealthProperties()));
        ReflectionTestUtils.setField(builder, "graphObservationProperties", new GraphObservationProperties());
        ReflectionTestUtils.setField(builder, "toolTimeoutProperties", new ToolTimeoutProperties());
        for (String field : List.of("conversationWindowManager", "progressLedgerService",
                "executionCheckpoint", "executionEvidenceRecorder", "toolResultStorage", "generatedFileCache")) {
            ReflectionTestUtils.setField(builder, field, null);
        }
        var guard = (ToolGuardService) ReflectionTestUtils.getField(builder, "toolGuardService");
        when(guard.evaluate(any(), eq(true))).thenReturn(GuardEvaluation.allow(TOOL));
        var disclosure = (ToolDisclosureService) ReflectionTestUtils.getField(builder, "toolDisclosureService");
        when(disclosure.split(any(), anySet(), anySet()))
                .thenReturn(new ToolDisclosureService.ToolDisclosureSplit(List.of(tool), List.of()));
        return builder;
    }

    private void writeDocuments() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(workspace.resolve("shipments.xlsx"))) {
            var sheet = workbook.createSheet("Shipments");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Total");
            row.createCell(1).setCellValue(149);
            workbook.write(out);
        }
        try (var document = new XWPFDocument(); var out = Files.newOutputStream(workspace.resolve("approval.docx"))) {
            document.createParagraph().createRun().setText("Approval: KESTREL-683-VIOLET");
            document.write(out);
        }
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<String> chunks = new CopyOnWriteArrayList<>();

        @Override public void send(SseEventBuilder builder) {
            builder.build().forEach(item -> chunks.add(String.valueOf(item.getData())));
        }

        String wire() { return String.join("", chunks); }
        long count(String name) { return wire().lines().filter(line -> line.equals("event:" + name)).count(); }
    }
}
