package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.service.WikiDirectoryScanService.ScanResult;

/**
 * 按来源分组扫描的薄封装：校验分组归属，委托扫描逻辑给 {@link WikiDirectoryScanService}，
 * 扫描完成后回写分组的 lastScanAt。
 *
 * @author MateClaw Team
 */
@Service
@RequiredArgsConstructor
public class WikiSourceGroupScanService {

    private final WikiSourceGroupService groupService;
    private final WikiDirectoryScanService scanService;

    public ScanResult scan(Long kbId, Long groupId, boolean full) {
        WikiSourceGroupEntity group = groupService.getById(groupId);
        if (group == null || !kbId.equals(group.getKbId())) {
            throw new MateClawException(404, "Source group not found in this knowledge base");
        }
        ScanResult result = scanService.scanGroup(kbId, group, full);
        groupService.touchLastScanAt(groupId);
        return result;
    }
}
