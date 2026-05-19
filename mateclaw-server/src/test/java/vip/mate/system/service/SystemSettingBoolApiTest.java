package vip.mate.system.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the typed accessors added for the lifecycle curator:
 * getBool / saveBool / getString / saveString.
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingBoolApiTest {

    @Mock
    private SystemSettingMapper mapper;

    private SystemSettingService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SystemSettingEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new SystemSettingService(mapper);
    }

    private SystemSettingEntity row(String value) {
        SystemSettingEntity e = new SystemSettingEntity();
        e.setSettingKey("k");
        e.setSettingValue(value);
        return e;
    }

    @Test
    void getBoolReturnsDefaultWhenKeyAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(service.getBool("k", true));
        assertFalse(service.getBool("k", false));
    }

    @Test
    void getBoolReadsTheStoredValue() {
        when(mapper.selectOne(any())).thenReturn(row("true"));
        assertTrue(service.getBool("k", false));
    }

    @Test
    void getStringReturnsTheStoredValue() {
        when(mapper.selectOne(any())).thenReturn(row("2026-05-19T02:00:00"));
        assertEquals("2026-05-19T02:00:00", service.getString("k", null));
    }

    @Test
    void saveBoolInsertsWhenKeyAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);
        service.saveBool("k", true, "desc");
        ArgumentCaptor<SystemSettingEntity> cap = ArgumentCaptor.forClass(SystemSettingEntity.class);
        verify(mapper).insert(cap.capture());
        assertEquals("true", cap.getValue().getSettingValue());
    }

    @Test
    void saveStringUpdatesWhenKeyPresent() {
        when(mapper.selectOne(any())).thenReturn(row("old"));
        service.saveString("k", "new", "desc");
        ArgumentCaptor<SystemSettingEntity> cap = ArgumentCaptor.forClass(SystemSettingEntity.class);
        verify(mapper).updateById(cap.capture());
        assertEquals("new", cap.getValue().getSettingValue());
    }
}
