package vip.mate.interop.a2a;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class A2aTaskSweeper {

    private final A2aProperties properties;
    private final A2aTaskStore store;

    @Scheduled(fixedDelayString = "${mateclaw.a2a.sweep-interval-ms:60000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        int removed = store.sweepExpired();
        if (removed > 0) {
            log.debug("A2A task sweep removed {} expired task(s)", removed);
        }
    }
}
