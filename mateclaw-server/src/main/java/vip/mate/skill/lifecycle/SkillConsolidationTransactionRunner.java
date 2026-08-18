package vip.mate.skill.lifecycle;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.BooleanSupplier;

/**
 * Gives each consolidation group its own transaction. Keeping this boundary in
 * a separate Spring bean ensures proxy interception; a self-invoked
 * {@code @Transactional} method would silently share the outer sweep.
 */
@Component
@RequiredArgsConstructor
public class SkillConsolidationTransactionRunner {

    private final PlatformTransactionManager transactionManager;

    public boolean execute(BooleanSupplier action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean result = transaction.execute(status -> action.getAsBoolean());
        return Boolean.TRUE.equals(result);
    }
}
