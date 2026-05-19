package vip.mate.wiki.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RFC-030: Wiki processing job lifecycle service — creates, transitions,
 * and records errors for processing jobs.
 */
@Slf4j
@Service
public class WikiProcessingJobService {

    private final WikiProcessingJobMapper jobMapper;
    private final WikiModelRoutingService routingService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AvailableProviderPool providerPool;

    @Autowired
    private ModelConfigService modelConfigService;

    public WikiProcessingJobService(WikiProcessingJobMapper jobMapper,
                                     WikiModelRoutingService routingService,
                                     ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
    }

    public WikiProcessingJobEntity createHeavyIngest(Long kbId, Long rawId) {
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setKbId(kbId);
        job.setRawId(rawId);
        job.setJobType(WikiJobType.HEAVY_INGEST.name().toLowerCase());
        job.setStage(WikiJobStage.QUEUED.name().toLowerCase());
        job.setStatus(WikiJobStatus.QUEUED.name().toLowerCase());
        job.setMaxRetries(3);
        job.setRetryCount(0);
        jobMapper.insert(job);
        return job;
    }

    public WikiProcessingJobEntity createLightEnrich(Long kbId, Long rawId) {
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setKbId(kbId);
        job.setRawId(rawId);
        job.setJobType(WikiJobType.LIGHT_ENRICH.name().toLowerCase());
        job.setStage(WikiJobStage.QUEUED.name().toLowerCase());
        job.setStatus(WikiJobStatus.QUEUED.name().toLowerCase());
        job.setMaxRetries(2);
        job.setRetryCount(0);
        jobMapper.insert(job);
        return job;
    }

    public WikiProcessingJobEntity createLocalRepair(Long kbId, Long rawId, Long targetPageId) {
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setKbId(kbId);
        job.setRawId(rawId);
        job.setJobType(WikiJobType.LOCAL_REPAIR.name().toLowerCase());
        job.setStage(WikiJobStage.QUEUED.name().toLowerCase());
        job.setStatus(WikiJobStatus.QUEUED.name().toLowerCase());
        job.setMaxRetries(2);
        job.setRetryCount(0);
        try {
            job.setMetaJson(objectMapper.writeValueAsString(Map.of("targetPageId", targetPageId)));
        } catch (Exception e) {
            job.setMetaJson("{}");
        }
        jobMapper.insert(job);
        return job;
    }

    public WikiProcessingJobEntity transition(Long jobId, WikiJobStage newStage) {
        WikiProcessingJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return null;

        job.setStage(newStage.name().toLowerCase());
        if (newStage == WikiJobStage.ROUTING) {
            job.setStartedAt(LocalDateTime.now());
            job.setStatus(WikiJobStatus.RUNNING.name().toLowerCase());
            job.setFallbackChainJson(routingService.buildFallbackChainJson(job.getKbId()));
        } else if (newStage == WikiJobStage.COMPLETED) {
            job.setFinishedAt(LocalDateTime.now());
            job.setStatus(WikiJobStatus.COMPLETED.name().toLowerCase());
        } else if (newStage == WikiJobStage.FAILED) {
            job.setFinishedAt(LocalDateTime.now());
            job.setStatus(WikiJobStatus.FAILED.name().toLowerCase());
        } else if (newStage == WikiJobStage.PARTIAL) {
            job.setFinishedAt(LocalDateTime.now());
            job.setStatus(WikiJobStatus.PARTIAL.name().toLowerCase());
        } else if (!newStage.isTerminal()) {
            // Non-terminal intermediate stage: mark as running
            job.setStatus(WikiJobStatus.RUNNING.name().toLowerCase());
        }
        jobMapper.updateById(job);
        return job;
    }

    public void recordHardError(Long jobId, String errorCode, String errorMessage) {
        WikiProcessingJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;

        job.setErrorCode(errorCode);
        job.setErrorMessage(truncate(errorMessage, 2000));
        job.setFinishedAt(LocalDateTime.now());

        if (job.getRetryCount() < job.getMaxRetries()) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setResumeFromStage(job.getStage());
            job.setStage(WikiJobStage.QUEUED.name().toLowerCase());
            job.setStatus(WikiJobStatus.QUEUED.name().toLowerCase());
        } else {
            job.setStatus(WikiJobStatus.FAILED.name().toLowerCase());
            job.setStage(WikiJobStage.FAILED.name().toLowerCase());
        }
        jobMapper.updateById(job);

        if (providerPool != null && isHardError(errorCode) && job.getCurrentModelId() != null) {
            notifyPoolHardError(job.getCurrentModelId(), errorCode);
        }
    }

    public void recordSoftError(Long jobId, String errorCode, String errorMessage) {
        WikiProcessingJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;

        job.setErrorCode(errorCode);
        job.setErrorMessage(truncate(errorMessage, 2000));
        job.setFinishedAt(LocalDateTime.now());

        if (job.getRetryCount() < job.getMaxRetries()) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setResumeFromStage(job.getStage());
            job.setStage(WikiJobStage.QUEUED.name().toLowerCase());
            job.setStatus(WikiJobStatus.QUEUED.name().toLowerCase());
        } else {
            job.setStatus(WikiJobStatus.FAILED.name().toLowerCase());
            job.setStage(WikiJobStage.FAILED.name().toLowerCase());
        }
        jobMapper.updateById(job);
    }

    /**
     * Recover stuck jobs on startup (routing or *_running → queued).
     */
    public void recoverOnStartup() {
        int recovered = jobMapper.recoverStuckJobs();
        if (recovered > 0) {
            log.info("[WikiJob] Recovered {} stuck jobs on startup", recovered);
        }
    }

    private static boolean isHardError(String errorCode) {
        // Only provider-wide failures evict a provider from the shared pool. A
        // MODEL_NOT_FOUND is model-scoped — it must not take the provider's other
        // models offline for every other consumer of the pool.
        return errorCode != null && (
            errorCode.equals("AUTH_ERROR") ||
            errorCode.equals("BILLING"));
    }

    private void notifyPoolHardError(Long modelId, String errorCode) {
        try {
            ModelConfigEntity model = modelConfigService.getModel(modelId);
            if (model != null) {
                AvailableProviderPool.RemovalSource source;
                try {
                    source = AvailableProviderPool.RemovalSource.valueOf(errorCode);
                } catch (IllegalArgumentException e) {
                    source = AvailableProviderPool.RemovalSource.AUTH_ERROR;
                }
                providerPool.remove(model.getProvider(), source, "Wiki job hard error: " + errorCode);
            }
        } catch (Exception e) {
            log.warn("[WikiJob] Failed to notify provider pool: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
