package vip.mate.wiki.event;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.service.WikiProcessingService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WikiProcessingListenerTransactionTest {
    @Test void workerStartsAfterCommitAndSeesPendingMaterial() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:wiki_listener_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table raw_material (id bigint primary key, status varchar(32))");
        var processor = mock(WikiProcessingService.class);
        var called = new CountDownLatch(1);
        var visibleRows = new AtomicInteger(-1);
        doAnswer(invocation -> {
            visibleRows.set(jdbc.queryForObject("select count(*) from raw_material where status='pending'", Integer.class));
            called.countDown();
            return null;
        }).when(processor).processRawMaterial(1L);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WikiProcessingListener.class, () -> new WikiProcessingListener(processor, new WikiProperties()));
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.refresh();
            new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx -> {
                jdbc.update("insert into raw_material values (1, 'pending')");
                context.publishEvent(new WikiProcessingEvent(this, 1L, 2L));
                try {
                    assertFalse(called.await(200, TimeUnit.MILLISECONDS), "Worker must not race the material transaction");
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(called.await(5, TimeUnit.SECONDS));
            assertEquals(1, visibleRows.get());
        }
    }

    @Test void rolledBackMaterialDoesNotStartAWorker() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:wiki_rollback_" + UUID.randomUUID(), "sa", "");
        var processor = mock(WikiProcessingService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WikiProcessingListener.class, () -> new WikiProcessingListener(processor, new WikiProperties()));
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.refresh();
            new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx -> {
                context.publishEvent(new WikiProcessingEvent(this, 1L, 2L));
                tx.setRollbackOnly();
            });
            verify(processor, after(200).never()).processRawMaterial(anyLong());
        }
    }

    @Test void nonTransactionalScanStillStartsAWorker() {
        var processor = mock(WikiProcessingService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WikiProcessingListener.class, () -> new WikiProcessingListener(processor, new WikiProperties()));
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.refresh();
            context.publishEvent(new WikiProcessingEvent(this, 1L, 2L));
            verify(processor, timeout(5000)).processRawMaterial(1L);
        }
    }
}
