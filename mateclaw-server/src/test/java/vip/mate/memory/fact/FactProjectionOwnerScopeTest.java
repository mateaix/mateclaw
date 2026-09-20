package vip.mate.memory.fact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.extraction.CompositeEntityExtractor;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.projection.FactProjectionBuilder;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactProjectionOwnerScopeTest {

    private static final long AGENT_ID = 1000000001L;

    @Test
    @DisplayName("full rebuild preserves two personal owners and shared TEAM scope for identical source refs")
    void rebuildPreservesCanonicalOwnerScope() {
        FactMapper mapper = mock(FactMapper.class);
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        CompositeEntityExtractor extractor = mock(CompositeEntityExtractor.class);
        MemoryProperties properties = new MemoryProperties();
        properties.getFact().setProjectionEnabled(true);

        WorkspaceFileEntity ownerA = metadata("structured/user.md", "user:a", MemoryScope.PERSONAL);
        WorkspaceFileEntity ownerB = metadata("structured/user.md", "user:b", MemoryScope.PERSONAL);
        WorkspaceFileEntity shared = metadata("structured/user.md", "", MemoryScope.TEAM);
        when(files.listFiles(AGENT_ID)).thenReturn(List.of(ownerA, ownerB, shared));
        when(files.getMemoryFile(AGENT_ID, "structured/user.md", "user:a"))
                .thenReturn(content(ownerA, "owner-a-content"));
        when(files.getMemoryFile(AGENT_ID, "structured/user.md", "user:b"))
                .thenReturn(content(ownerB, "owner-b-content"));
        when(files.getFile(AGENT_ID, "structured/user.md"))
                .thenReturn(content(shared, "shared-content"));
        when(extractor.extract(eq(AGENT_ID), eq("structured/user.md"), anyString()))
                .thenReturn(List.of(new ExtractedFact("structured/user.md#preferred_language",
                        "user_pref", "preferred_language", "is", "Chinese", 0.9, 0.8, "pattern")));
        when(mapper.selectOne(any())).thenReturn(null);
        AtomicLong ids = new AtomicLong(10);
        doAnswer(invocation -> {
            FactEntity fact = invocation.getArgument(0);
            fact.setId(ids.incrementAndGet());
            return 1;
        }).when(mapper).insert(any(FactEntity.class));

        FactProjectionBuilder builder = new FactProjectionBuilder(mapper, files, extractor, properties);

        assertEquals(3, builder.rebuildAll(AGENT_ID));

        ArgumentCaptor<FactEntity> inserted = ArgumentCaptor.forClass(FactEntity.class);
        verify(mapper, times(3)).insert(inserted.capture());
        List<FactEntity> projected = inserted.getAllValues();
        assertTrue(projected.stream().anyMatch(f -> "user:a".equals(f.getOwnerKey())
                && MemoryScope.PERSONAL.equals(f.getScope())));
        assertTrue(projected.stream().anyMatch(f -> "user:b".equals(f.getOwnerKey())
                && MemoryScope.PERSONAL.equals(f.getScope())));
        assertTrue(projected.stream().anyMatch(f -> "".equals(f.getOwnerKey())
                && MemoryScope.TEAM.equals(f.getScope())));
        verify(files).getMemoryFile(AGENT_ID, "structured/user.md", "user:a");
        verify(files).getMemoryFile(AGENT_ID, "structured/user.md", "user:b");
    }

    private static WorkspaceFileEntity metadata(String filename, String ownerKey, String scope) {
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setFilename(filename);
        file.setOwnerKey(ownerKey);
        file.setScope(scope);
        return file;
    }

    private static WorkspaceFileEntity content(WorkspaceFileEntity source, String content) {
        WorkspaceFileEntity file = metadata(source.getFilename(), source.getOwnerKey(), source.getScope());
        file.setContent(content);
        return file;
    }
}
