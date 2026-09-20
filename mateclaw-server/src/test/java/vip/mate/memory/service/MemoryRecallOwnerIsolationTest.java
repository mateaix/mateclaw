package vip.mate.memory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.MemoryRecallMapper;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workspace.document.repository.WorkspaceFileMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRecallOwnerIsolationTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MemoryRecallEntity.class);
    }

    @Test
    @DisplayName("tracker records shared and current-owner files but rejects another owner's row")
    void trackerPropagatesOnlyVisibleOwnerIdentity() {
        MemoryRecallService recallService = mock(MemoryRecallService.class);
        WorkspaceFileMapper fileMapper = mock(WorkspaceFileMapper.class);
        WorkspaceFileEntity shared = file("MEMORY.md", "shared", "", MemoryScope.TEAM);
        WorkspaceFileEntity ownerA = file("PROFILE.md", "owner-a", "user:a", MemoryScope.PERSONAL);
        WorkspaceFileEntity ownerB = file("PROFILE.md", "owner-b", "user:b", MemoryScope.PERSONAL);
        when(fileMapper.selectList(any())).thenReturn(List.of(shared, ownerA, ownerB));

        new MemoryRecallTracker(recallService, fileMapper)
                .trackRecalls(7L, "what do you remember?", "user:a");

        verify(recallService).recordRecall(eq(7L), eq("MEMORY.md"), eq("shared"),
                anyString(), eq(""), eq(MemoryScope.TEAM));
        verify(recallService).recordRecall(eq(7L), eq("PROFILE.md"), eq("owner-a"),
                anyString(), eq("user:a"), eq(MemoryScope.PERSONAL));
        verify(recallService, never()).recordRecall(eq(7L), eq("PROFILE.md"), eq("owner-b"),
                anyString(), eq("user:b"), eq(MemoryScope.PERSONAL));
    }

    @Test
    @DisplayName("owner-aware ledger insert persists PERSONAL scope and owner")
    void recordRecallPersistsPersonalIdentity() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        MemoryRecallService service = new MemoryRecallService(mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(7L, "PROFILE.md", "private preference", "query-hash",
                "user:a", MemoryScope.PERSONAL);

        ArgumentCaptor<MemoryRecallEntity> inserted = ArgumentCaptor.forClass(MemoryRecallEntity.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("user:a", inserted.getValue().getOwnerKey());
        assertEquals(MemoryScope.PERSONAL, inserted.getValue().getScope());
    }

    @Test
    @DisplayName("legacy recordRecall overload remains shared with canonical empty owner")
    void legacyRecordRecallRemainsShared() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        MemoryRecallService service = new MemoryRecallService(mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(7L, "MEMORY.md", "shared memory", "query-hash");

        ArgumentCaptor<MemoryRecallEntity> inserted = ArgumentCaptor.forClass(MemoryRecallEntity.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("", inserted.getValue().getOwnerKey());
        assertEquals(MemoryScope.TEAM, inserted.getValue().getScope());
    }

    @Test
    @DisplayName("existing recall uses an atomic SQL increment without inserting")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void existingRecallIsIncrementedAtomically() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        MemoryRecallService service = new MemoryRecallService(mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(7L, "MEMORY.md", "shared memory", null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MemoryRecallEntity>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(eq(null), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("recall_count = COALESCE(recall_count, 0) + 1"));
        assertTrue(wrapper.getValue().getSqlSet().contains("daily_count = COALESCE(daily_count, 0) + 1"));
        verify(mapper, never()).insert(any(MemoryRecallEntity.class));
    }

    @Test
    @DisplayName("duplicate insert race retries the atomic update")
    void duplicateInsertRetriesIncrement() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.update(any(), any())).thenReturn(0, 1);
        when(mapper.insert(any(MemoryRecallEntity.class))).thenThrow(new DuplicateKeyException("raced"));
        MemoryRecallService service = new MemoryRecallService(mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(7L, "MEMORY.md", "shared memory", null);

        verify(mapper, times(2)).update(any(), any());
        verify(mapper).insert(any(MemoryRecallEntity.class));
    }

    @Test
    @DisplayName("shared Dream candidate query excludes PERSONAL scope")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dreamCandidatesStaySharedOnly() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        MemoryRecallService service = new MemoryRecallService(mapper, new MemoryProperties(), new ObjectMapper());

        service.listCandidates(7L);

        ArgumentCaptor<LambdaQueryWrapper<MemoryRecallEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        query.getValue().getSqlSegment();
        String parameters = query.getValue().getParamNameValuePairs().values().toString();
        assertTrue(parameters.contains(MemoryScope.TEAM));
        assertTrue(parameters.contains(MemoryScope.GLOBAL));
        assertFalse(parameters.contains(MemoryScope.PERSONAL));
    }

    private static WorkspaceFileEntity file(String filename, String content, String ownerKey, String scope) {
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setFilename(filename);
        file.setContent(content);
        file.setOwnerKey(ownerKey);
        file.setScope(scope);
        file.setEnabled(true);
        return file;
    }
}
