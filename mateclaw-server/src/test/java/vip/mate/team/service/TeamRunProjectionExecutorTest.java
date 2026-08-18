package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.repository.TeamTaskMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamRunProjectionExecutorTest {

    @Test
    void executeDelegatesInANewTransaction() throws Exception {
        TeamRunProjector projector = mock(TeamRunProjector.class);
        TeamTaskMapper taskMapper = mock(TeamTaskMapper.class);
        TeamRunProjectionExecutor executor = new TeamRunProjectionExecutor(projector, taskMapper);

        executor.execute(20L);

        verify(projector).project(20L);
        Transactional transactional = TeamRunProjectionExecutor.class
                .getDeclaredMethod("execute", Long.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void executeTaskLooksUpRunAndProjectsInANewTransaction() throws Exception {
        TeamRunProjector projector = mock(TeamRunProjector.class);
        TeamTaskMapper taskMapper = mock(TeamTaskMapper.class);
        TeamTaskEntity task = new TeamTaskEntity();
        task.setRunId(20L);
        when(taskMapper.selectById(5L)).thenReturn(task);
        TeamRunProjectionExecutor executor = new TeamRunProjectionExecutor(projector, taskMapper);

        executor.executeTask(5L);

        verify(taskMapper).selectById(5L);
        verify(projector).project(20L);
        Transactional transactional = TeamRunProjectionExecutor.class
                .getDeclaredMethod("executeTask", Long.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void executeTaskSkipsTasksWithoutRuns() {
        TeamRunProjector projector = mock(TeamRunProjector.class);
        TeamTaskMapper taskMapper = mock(TeamTaskMapper.class);
        when(taskMapper.selectById(5L)).thenReturn(new TeamTaskEntity());
        TeamRunProjectionExecutor executor = new TeamRunProjectionExecutor(projector, taskMapper);

        executor.executeTask(5L);

        verify(projector, never()).project(20L);
    }
}
