package vip.mate.skill.installer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/** Separate proxy boundary reuses AsyncSecurityConfig's bounded, identity-aware executor. */
@Component
public class SkillInstallWorker {
    @Async
    public CompletableFuture<Void> run(Runnable install) {
        install.run();
        return CompletableFuture.completedFuture(null);
    }
}
