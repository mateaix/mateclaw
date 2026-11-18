package vip.mate.skill.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import vip.mate.skill.installer.model.*;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.*;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillInstallCancellationTest {
    final BundleResolver resolver = mock(BundleResolver.class);
    final SkillWorkspaceManager workspace = mock(SkillWorkspaceManager.class);
    final SkillService skills = mock(SkillService.class);
    final SkillFileService files = mock(SkillFileService.class);
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
    volatile Thread worker;
    SkillInstaller installer;
    org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    @BeforeEach void setup() {
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                vip.mate.config.AsyncSecurityConfig.class, SkillInstallWorker.class);
        installer = new SkillInstaller(resolver,null,workspace,skills,files,new ObjectMapper(),event -> {}, context.getBean(SkillInstallWorker.class));
        when(skills.listSkills(1L)).thenReturn(List.of());
        when(skills.createSkill(any())).thenAnswer(i -> { SkillEntity entity = i.getArgument(0); entity.setId(42L); return entity; });
        when(files.applyBundleBytes(anyLong(),anyMap(),anyBoolean())).thenReturn(new SkillFileService.ApplyResult(0,0,false,false));
        when(workspace.applyBundleBytes(anyString(),anyMap(),anyBoolean(),eq(1L)))
                .thenReturn(new SkillWorkspaceManager.ApplyBundleResult(0,0,false,0,0,false));
        when(workspace.resolveConventionPath(anyString(),eq(1L))).thenReturn(java.nio.file.Path.of("/unused/test"));
    }
    @AfterEach void cleanup() { release.countDown(); context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    private InstallRequest request() { var r = new InstallRequest(); r.setBundleUrl("https://github.com/test/skill"); r.setWorkspaceId(1L); return r; }
    private SkillBundle bundle() { return new SkillBundle("test","# test",Map.of(),Map.of(),"github","test","1","","",""); }
    private void awaitRelease() {
        worker = Thread.currentThread(); entered.countDown();
        try { release.await(3,TimeUnit.SECONDS); } catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
    }
    @SuppressWarnings("unchecked") private InstallTask task() throws Exception {
        var field = SkillInstaller.class.getDeclaredField("tasks"); field.setAccessible(true);
        return ((Map<String,InstallTask>)field.get(installer)).values().iterator().next();
    }
    @Test void startReturnsTaskBeforeDownloadCompletes() {
        when(resolver.resolve(anyString(),isNull())).thenAnswer(i -> { awaitRelease(); return null; });
        var task = assertTimeoutPreemptively(Duration.ofMillis(500), () -> installer.startInstall(request()));
        assertNotNull(task.getTaskId()); assertNotEquals(InstallTask.InstallStatus.COMPLETED,task.getStatus());
    }
    @Test void cancelInterruptsDownloadAndCannotBecomeFailedOrWriteFiles() throws Exception {
        when(resolver.resolve(anyString(),isNull())).thenAnswer(i -> { awaitRelease(); return null; });
        var start = CompletableFuture.supplyAsync(() -> installer.startInstall(request()));
        assertTrue(entered.await(1,TimeUnit.SECONDS)); var task = task();
        installer.cancelTask(task.getTaskId());
        assertTrue(interrupted.await(1,TimeUnit.SECONDS),"cancellation must interrupt the in-flight download");
        release.countDown(); start.get(1,TimeUnit.SECONDS); worker.join(1000);
        assertEquals(InstallTask.InstallStatus.CANCELLED,task.getStatus());
        verifyNoInteractions(workspace,skills,files);
    }
    @Test void cancellationAfterCommitStartsCannotArchiveExistingWorkspace() throws Exception {
        when(resolver.resolve(anyString(),isNull())).thenReturn(bundle());
        doAnswer(i -> { awaitRelease(); return null; }).when(workspace).initWorkspace(anyString(),anyString(),anyBoolean(),eq(1L));
        var start = CompletableFuture.supplyAsync(() -> installer.startInstall(request()));
        assertTrue(entered.await(1,TimeUnit.SECONDS)); var task = task();
        installer.cancelTask(task.getTaskId());
        assertNotEquals(InstallTask.InstallStatus.CANCELLED,task.getStatus());
        release.countDown(); start.get(1,TimeUnit.SECONDS); worker.join(1000);
        assertEquals(InstallTask.InstallStatus.COMPLETED,task.getStatus());
        verify(workspace,never()).archiveWorkspace(anyString(),anyLong());
    }
    @Test void callerIdentityAndRequestSnapshotArePreservedAcrossWorkerBoundary() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("installer-user","ignored"));
        var identity = new java.util.concurrent.atomic.AtomicReference<String>();
        when(resolver.resolve(anyString(),isNull())).thenAnswer(i -> {
            identity.set(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName());
            awaitRelease(); return bundle();
        });
        var request = request(); request.setTargetName("original-name");
        var task = installer.startInstall(request);
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        request.setTargetName("changed-name"); request.setWorkspaceId(2L);
        // Mutation of the caller-owned DTO must not change a queued task's workspace/name.
        release.countDown(); worker.join(1000);
        assertEquals("installer-user",identity.get());
        verify(workspace).initWorkspace("original-name","# test",false,1L);
        assertEquals(InstallTask.InstallStatus.COMPLETED,task.getStatus());
    }
    @Test void workerCannotLeaveTaskUntilItsCancellationInterruptIsDelivered() throws Exception {
        when(resolver.resolve(anyString(),isNull())).thenAnswer(i -> { awaitRelease(); return null; });
        var task = installer.startInstall(request()); assertTrue(entered.await(1,TimeUnit.SECONDS));
        var field = SkillInstaller.class.getDeclaredField("installThreads"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var original = (java.util.concurrent.ConcurrentHashMap<String,Thread>)field.get(installer);
        var lookupStarted = new CountDownLatch(1); var allowLookup = new CountDownLatch(1);
        var paused = new java.util.concurrent.ConcurrentHashMap<String,Thread>(original) {
            @Override public Thread get(Object key) {
                lookupStarted.countDown();
                try { allowLookup.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.get(key);
            }
        };
        field.set(installer,paused);
        var cancelling = Thread.ofVirtual().start(() -> installer.cancelTask(task.getTaskId()));
        try {
            assertTrue(lookupStarted.await(1,TimeUnit.SECONDS)); release.countDown(); worker.join(150);
            assertTrue(worker.isAlive(),"worker must retain ownership until the cancellation interrupt has been delivered");
        } finally { allowLookup.countDown(); cancelling.join(1000); worker.join(1000); }
        assertEquals(InstallTask.InstallStatus.CANCELLED,task.getStatus());
    }
}
