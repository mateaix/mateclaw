package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 旧数据一次性回填：把每个知识库既有的换行分隔 {@code sourceDirectory} 拆分成若干条
 * {@link WikiSourceGroupEntity}，别名优先用路径本身（超长/重复截断+序号兜底）。
 * <p>
 * 幂等：若该 KB 已存在任意分组（不论谁创建）则整体跳过，不重复生成。历史 raw material
 * 的 groupId 不在此一次性回填——风险高、容易匹配错，交给该分组下一次正常扫描时
 * （见 {@link WikiDirectoryScanService#scanGroup}）自然、安全地补写。
 *
 * <p>Order(220) — runs after {@code DatabaseBootstrapRunner}(200) / 其它模块的
 * bootstrap runner，此时表结构（V168/V169）已就绪。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(220)
@RequiredArgsConstructor
public class WikiSourceGroupBackfillRunner implements ApplicationRunner {

    private static final int MAX_ALIAS_LENGTH = 64;

    private final WikiKnowledgeBaseService kbService;
    private final WikiSourceGroupService groupService;

    @Override
    public void run(ApplicationArguments args) {
        List<WikiKnowledgeBaseEntity> kbs = kbService.listAll();
        int migratedKbs = 0;
        int createdGroups = 0;
        for (WikiKnowledgeBaseEntity kb : kbs) {
            String dir = kb.getSourceDirectory();
            if (dir == null || dir.isBlank()) {
                continue;
            }
            if (!groupService.listByKbId(kb.getId()).isEmpty()) {
                continue;
            }
            List<String> patterns = WikiSourcePathValidator.parseSourcePatterns(dir);
            if (patterns.isEmpty()) {
                continue;
            }
            Set<String> usedAliases = new HashSet<>();
            for (String pattern : patterns) {
                String alias = uniqueAlias(pattern, usedAliases);
                try {
                    groupService.create(kb.getId(), alias, pattern, null, null, true);
                    createdGroups++;
                } catch (IllegalArgumentException e) {
                    log.warn("[Wiki] Skipped backfilling source group for kb={} pattern='{}': {}",
                            kb.getId(), pattern, e.getMessage());
                }
            }
            migratedKbs++;
        }
        if (migratedKbs > 0) {
            log.info("[Wiki] Source group backfill: migratedKbs={}, createdGroups={}", migratedKbs, createdGroups);
        }
    }

    private String uniqueAlias(String pattern, Set<String> usedAliases) {
        String base = pattern.length() > MAX_ALIAS_LENGTH ? pattern.substring(0, MAX_ALIAS_LENGTH) : pattern;
        String alias = base;
        int suffix = 2;
        while (!usedAliases.add(alias)) {
            alias = base + "-" + suffix++;
        }
        return alias;
    }
}
