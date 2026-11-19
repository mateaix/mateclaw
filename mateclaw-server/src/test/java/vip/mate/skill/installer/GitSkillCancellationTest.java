package vip.mate.skill.installer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.SkillFrontmatterParser;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GitSkillCancellationTest {
    @Test void interruptedCloneDestroysProcessTreeAndPreservesInterrupt() throws Exception {
        var process = mock(Process.class); var child = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(i -> java.util.stream.Stream.of(child));
        when(process.waitFor(anyLong(),any(TimeUnit.class))).thenThrow(new InterruptedException("cancel"));
        try (var builders = mockConstruction(ProcessBuilder.class,(builder,context) -> when(builder.start()).thenReturn(process))) {
            try {
                assertNull(new GitSkillFetcher(new SkillFrontmatterParser(),"").fetch("https://example.invalid/fixture.git",null,null));
                verify(child).destroyForcibly(); verify(process).destroyForcibly();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }
    @Test void timeoutKillsChildrenBeforeParentExactlyOnce() throws Exception {
        var process = mock(Process.class); var child = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(i -> java.util.stream.Stream.of(child));
        when(process.waitFor(anyLong(),any(TimeUnit.class))).thenReturn(false,true);
        try (var builders = mockConstruction(ProcessBuilder.class,(builder,context) -> when(builder.start()).thenReturn(process))) {
            assertNull(new GitSkillFetcher(new SkillFrontmatterParser(),"").fetch("https://example.invalid/fixture.git",null,null));
            var order = inOrder(child,process);
            order.verify(child).destroyForcibly(); order.verify(process).destroyForcibly();
            verify(process,times(1)).destroyForcibly();
        }
    }
}
