package vip.mate.skill.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.skill.runtime.model.ResolvedSkill;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillScanFailureBoundaryTest {
    @Test void resolverDoesNotTurnAScannerCrashIntoAnAllowedSkill() {
        var scanner = mock(SkillSecurityService.class);
        when(scanner.validate(any())).thenThrow(new IllegalStateException("private diagnostic detail"));
        var resolver = new SkillPackageResolver(null,null,null,scanner,null,new ObjectMapper(),null,null,null,null,null,null,null);
        var resolved = ResolvedSkill.builder().name("test").builtin(false).content("# Test").build();
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        assertTrue(resolved.isSecurityBlocked());
        assertTrue(resolved.getSecurityFindings().stream().anyMatch(f -> "SCAN_INCOMPLETE".equals(f.getCategory())));
        assertFalse(resolved.getSecuritySummary().contains("private diagnostic detail"));
    }
    @Test void explicitBuiltinTrustPolicyIsUnchanged() {
        var scanner = mock(SkillSecurityService.class);
        var resolver = new SkillPackageResolver(null,null,null,scanner,null,new ObjectMapper(),null,null,null,null,null,null,null);
        var resolved = ResolvedSkill.builder().name("builtin").builtin(true).content("# Test").build();
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        assertFalse(resolved.isSecurityBlocked());
        verifyNoInteractions(scanner);
    }
    @Test void explicitRescanDoesNotReuseTheOldContentOnlyVerdict() {
        var scanner = mock(SkillSecurityService.class);
        when(scanner.validate(any())).thenReturn(SkillValidationResult.pass("test"), SkillValidationResult.block("test", java.util.List.of(), java.util.List.of()));
        var resolver = spy(new SkillPackageResolver(null,null,null,scanner,null,new ObjectMapper(),null,null,null,null,null,null,null));
        var resolved = ResolvedSkill.builder().id(7L).name("test").builtin(false).content("# unchanged").build();
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        var entity = new vip.mate.skill.model.SkillEntity(); entity.setId(7L); entity.setName("test");
        doAnswer(invocation -> { ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved); return resolved; }).when(resolver).resolve(entity);
        var runtime = new SkillRuntimeService(null,resolver,null,null,null,null);
        assertTrue(runtime.rescanSingle(entity).isSecurityBlocked());
        verify(scanner, times(2)).validate(resolved);
    }

    @Test void incompleteVerdictsAreRetriedRatherThanCachedForever() {
        var scanner = mock(SkillSecurityService.class);
        var incomplete = SkillValidationResult.block("test", java.util.List.of(SkillValidationResult.Finding.builder().category("SCAN_INCOMPLETE").severity(SkillValidationResult.Severity.HIGH).build()), java.util.List.of());
        when(scanner.validate(any())).thenReturn(incomplete, SkillValidationResult.pass("test"));
        var resolver = new SkillPackageResolver(null,null,null,scanner,null,new ObjectMapper(),null,null,null,null,null,null,null);
        var resolved = ResolvedSkill.builder().id(9L).name("test").builtin(false).content("# unchanged").build();
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        assertTrue(resolved.isSecurityBlocked());
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        assertFalse(resolved.isSecurityBlocked());
        assertTrue(resolved.getSecurityFindings().isEmpty());
        verify(scanner, times(2)).validate(resolved);
    }

    @Test void scannerFailureAlsoInvalidatesAnOlderSuccessfulHash() {
        var scanner = mock(SkillSecurityService.class);
        when(scanner.validate(any())).thenReturn(SkillValidationResult.pass("test"))
                .thenThrow(new IllegalStateException("scan failed"))
                .thenReturn(SkillValidationResult.pass("test"));
        var resolver = new SkillPackageResolver(null,null,null,scanner,null,new ObjectMapper(),null,null,null,null,null,null,null);
        var resolved = ResolvedSkill.builder().id(11L).name("test").builtin(false).content("A").build();
        ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        resolved.setContent("B"); ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        assertTrue(resolved.isSecurityBlocked());
        resolved.setContent("A"); ReflectionTestUtils.invokeMethod(resolver, "applySecurity", resolved);
        verify(scanner, times(3)).validate(resolved);
    }

}
