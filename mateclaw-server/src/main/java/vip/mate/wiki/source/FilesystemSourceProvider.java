package vip.mate.wiki.source;

import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiDirectoryScanService;

/**
 * The built-in filesystem source: syncs a KB by scanning its configured source
 * directory (path validation, symlink resolution and content-hash change
 * detection live in the scan service).
 *
 * @author MateClaw Team
 */
@Component
public class FilesystemSourceProvider implements WikiIngestSourceProvider {

    private final WikiDirectoryScanService scanService;

    public FilesystemSourceProvider(WikiDirectoryScanService scanService) {
        this.scanService = scanService;
    }

    @Override
    public String sourceType() {
        return "filesystem";
    }

    @Override
    public boolean supports(WikiKnowledgeBaseEntity kb) {
        return kb != null && kb.getSourceDirectory() != null && !kb.getSourceDirectory().isBlank();
    }

    @Override
    public WikiDirectoryScanService.ScanResult sync(WikiKnowledgeBaseEntity kb) {
        return scanService.scanDirectory(kb.getId(), kb.getSourceDirectory());
    }
}
