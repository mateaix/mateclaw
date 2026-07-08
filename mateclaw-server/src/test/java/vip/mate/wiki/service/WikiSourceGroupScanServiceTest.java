package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiSourceGroupEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin-wrapper behavior of {@link WikiSourceGroupScanService}: validates group
 * ownership before delegating, and always touches lastScanAt after a scan.
 */
class WikiSourceGroupScanServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long GROUP_ID = 42L;

    private WikiSourceGroupService groupService;
    private WikiDirectoryScanService scanService;
    private WikiSourceGroupScanService service;

    @BeforeEach
    void setUp() {
        groupService = mock(WikiSourceGroupService.class);
        scanService = mock(WikiDirectoryScanService.class);
        service = new WikiSourceGroupScanService(groupService, scanService);
    }

    private WikiSourceGroupEntity group(Long kbId) {
        WikiSourceGroupEntity g = new WikiSourceGroupEntity();
        g.setId(GROUP_ID);
        g.setKbId(kbId);
        return g;
    }

    @Test
    @DisplayName("scan: group not found → MateClawException")
    void scan_groupNotFoundThrows() {
        when(groupService.getById(GROUP_ID)).thenReturn(null);

        assertThrows(MateClawException.class, () -> service.scan(KB_ID, GROUP_ID, false));
    }

    @Test
    @DisplayName("scan: group belongs to a different KB → MateClawException")
    void scan_groupFromDifferentKbThrows() {
        when(groupService.getById(GROUP_ID)).thenReturn(group(999L));

        assertThrows(MateClawException.class, () -> service.scan(KB_ID, GROUP_ID, false));
    }

    @Test
    @DisplayName("scan: delegates to scanService.scanGroup and touches lastScanAt")
    void scan_delegatesAndTouchesLastScanAt() {
        WikiSourceGroupEntity g = group(KB_ID);
        when(groupService.getById(GROUP_ID)).thenReturn(g);
        WikiDirectoryScanService.ScanResult expected = new WikiDirectoryScanService.ScanResult(3, 1, 2, List.of());
        when(scanService.scanGroup(eq(KB_ID), eq(g), eq(true))).thenReturn(expected);

        WikiDirectoryScanService.ScanResult result = service.scan(KB_ID, GROUP_ID, true);

        org.junit.jupiter.api.Assertions.assertEquals(expected, result);
        verify(groupService).touchLastScanAt(GROUP_ID);
    }
}
