package vip.mate.system.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.plugin.PluginManager;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.workspace.core.config.WorkspaceSandboxProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Covers the default workspace storage root setting: validation, immediate
 * registration with {@link WorkspacePathGuard}, clearing back to the yml/env
 * default, and the partial-payload (null) no-op contract.
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingWorkspaceStorageRootTest {

    @Mock
    private SystemSettingMapper mapper;

    @TempDir
    Path tempDir;

    private WorkspaceSandboxProperties sandboxProperties;
    private SystemSettingService service;
    private final Map<String, String> store = new HashMap<>();
    private Path originalDefaultRoot;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SystemSettingEntity.class);
    }

    @BeforeEach
    void setUp() {
        originalDefaultRoot = WorkspacePathGuard.getDefaultRoot();
        sandboxProperties = new WorkspaceSandboxProperties();
        sandboxProperties.setRoot(tempDir.resolve("yml-default").toString());
        service = new SystemSettingService(mapper, new SearchProviderRegistry(List.of()),
                new SettingCrypto("test-key"), sandboxProperties, mock(PluginManager.class));

        // Back the mapper with an in-memory map so saveValue/getValue round-trip.
        store.clear();
        lenient().when(mapper.selectOne(any())).thenAnswer(inv -> null);
        lenient().when(mapper.selectList(any())).thenAnswer(inv -> List.of());
        lenient().when(mapper.insert(any(SystemSettingEntity.class))).thenAnswer(inv -> {
            SystemSettingEntity e = inv.getArgument(0);
            store.put(e.getSettingKey(), e.getSettingValue());
            return 1;
        });
    }

    @AfterEach
    void restoreGuard() {
        WorkspacePathGuard.setDefaultRoot(originalDefaultRoot == null ? null : originalDefaultRoot.toString());
    }

    private SystemSettingsDTO dtoWithRoot(String root) {
        SystemSettingsDTO dto = new SystemSettingsDTO();
        dto.setWorkspaceStorageRoot(root);
        return dto;
    }

    @Test
    @DisplayName("relative path is rejected")
    void relativePathRejected() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.saveSettings(dtoWithRoot("data/workspace")));
        assertEquals("err.settings.storage_root_not_absolute", ex.getMsgKey());
        assertNull(store.get("workspace.storage_root"),
                "the storage root must not be persisted on validation failure");
    }

    @Test
    @DisplayName("absolute path is persisted and registered immediately")
    void absolutePathApplied() {
        Path newRoot = tempDir.resolve("custom-root");
        service.saveSettings(dtoWithRoot(newRoot.toString()));

        assertEquals(newRoot.toString(), store.get("workspace.storage_root"));
        assertEquals(newRoot.toAbsolutePath().normalize(), WorkspacePathGuard.getDefaultRoot());
        assertTrue(newRoot.toFile().isDirectory(), "directory should be created on save");
    }

    @Test
    @DisplayName("blank value clears the override and falls back to the yml root")
    void blankClearsOverride() {
        service.saveSettings(dtoWithRoot(tempDir.resolve("custom-root").toString()));
        service.saveSettings(dtoWithRoot(""));

        assertEquals("", store.get("workspace.storage_root"));
        assertEquals(Paths.get(sandboxProperties.getRoot()).toAbsolutePath().normalize(),
                WorkspacePathGuard.getDefaultRoot());
    }

    @Test
    @DisplayName("blank value with sandbox disabled clears the guard entirely")
    void blankWithSandboxDisabled() {
        sandboxProperties.setEnabled(false);
        service.saveSettings(dtoWithRoot(""));
        assertNull(WorkspacePathGuard.getDefaultRoot());
    }

    @Test
    @DisplayName("null (field not submitted) leaves the stored value untouched")
    void nullFieldIsNoOp() {
        service.saveSettings(new SystemSettingsDTO());
        assertNull(store.get("workspace.storage_root"));
    }
}
