package vip.mate.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C.8 — Tests for SoulSummarizerService: K-accumulate trigger + SOUL update.
 */
@ExtendWith(MockitoExtension.class)
class SoulSummarizerServiceTest {

    @Mock private WorkspaceFileService workspaceFileService;
    @Mock private ModelConfigService modelConfigService;
    @Mock private AgentGraphBuilder agentGraphBuilder;
    @Mock private org.springframework.ai.chat.model.ChatModel chatModel;

    private MemoryProperties props;
    private SoulSummarizerService service;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        service = new SoulSummarizerService(workspaceFileService, modelConfigService,
                agentGraphBuilder, props);
    }

    @Test
    @DisplayName("soulUpdateInterval=0: no SOUL update triggered")
    void intervalZero_noUpdate() {
        props.setSoulUpdateInterval(0);
        for (int i = 0; i < 100; i++) {
            service.onMemoryWrite(new MemoryWriteEvent(1L, "MEMORY.md", "consolidate", "content"));
        }
        verify(workspaceFileService, never()).saveFile(eq(1L), eq("SOUL.md"), any());
    }

    @Test
    @DisplayName("soulUpdateInterval=5: first 4 writes are no-op, 5th triggers update")
    void interval5_triggersOn5th() {
        props.setSoulUpdateInterval(5);

        // Mock LLM for when it triggers
        ModelConfigEntity model = new ModelConfigEntity();
        model.setProvider("mock");
        lenient().when(modelConfigService.getDefaultModel()).thenReturn(model);
        lenient().when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);

        var chatResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
        var generation = mock(org.springframework.ai.chat.model.Generation.class);
        var output = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        lenient().when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        lenient().when(chatResponse.getResult()).thenReturn(generation);
        lenient().when(generation.getOutput()).thenReturn(output);
        lenient().when(output.getText()).thenReturn("_Updated SOUL content that is longer than 50 chars to pass the length check._");

        // Mock file reads
        WorkspaceFileEntity soulFile = new WorkspaceFileEntity();
        soulFile.setContent("old soul");
        lenient().when(workspaceFileService.getFile(1L, "SOUL.md")).thenReturn(soulFile);
        lenient().when(workspaceFileService.getFile(1L, "MEMORY.md")).thenReturn(soulFile);
        lenient().when(workspaceFileService.getFile(1L, "PROFILE.md")).thenReturn(soulFile);

        // First 4 writes: no SOUL update
        for (int i = 0; i < 4; i++) {
            service.onMemoryWrite(new MemoryWriteEvent(1L, "MEMORY.md", "remember", "c" + i));
        }
        verify(workspaceFileService, never()).saveFile(eq(1L), eq("SOUL.md"), any());

        // 5th write: triggers SOUL update
        service.onMemoryWrite(new MemoryWriteEvent(1L, "structured/user.md", "remember", "c4"));
        verify(workspaceFileService, times(1)).saveFile(eq(1L), eq("SOUL.md"), any());
    }

    @Test
    @DisplayName("Counter resets after trigger: needs another K writes for next update")
    void counterResets_afterTrigger() {
        props.setSoulUpdateInterval(3);

        ModelConfigEntity model = new ModelConfigEntity();
        lenient().when(modelConfigService.getDefaultModel()).thenReturn(model);
        lenient().when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(chatModel);

        var chatResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
        var generation = mock(org.springframework.ai.chat.model.Generation.class);
        var output = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        lenient().when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        lenient().when(chatResponse.getResult()).thenReturn(generation);
        lenient().when(generation.getOutput()).thenReturn(output);
        lenient().when(output.getText()).thenReturn("New SOUL content with enough length to pass the fifty character minimum threshold check.");

        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent("content");
        lenient().when(workspaceFileService.getFile(eq(1L), any())).thenReturn(file);

        // Trigger 1st update at write #3
        for (int i = 0; i < 3; i++) {
            service.onMemoryWrite(new MemoryWriteEvent(1L, "MEMORY.md", "remember", "x"));
        }
        verify(workspaceFileService, times(1)).saveFile(eq(1L), eq("SOUL.md"), any());

        // Next 2 writes: no update yet
        for (int i = 0; i < 2; i++) {
            service.onMemoryWrite(new MemoryWriteEvent(1L, "MEMORY.md", "remember", "y"));
        }
        verify(workspaceFileService, times(1)).saveFile(eq(1L), eq("SOUL.md"), any());

        // 3rd write after reset: triggers 2nd update
        service.onMemoryWrite(new MemoryWriteEvent(1L, "MEMORY.md", "remember", "z"));
        verify(workspaceFileService, times(2)).saveFile(eq(1L), eq("SOUL.md"), any());
    }
}
