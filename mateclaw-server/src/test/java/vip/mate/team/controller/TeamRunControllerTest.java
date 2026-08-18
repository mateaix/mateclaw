package vip.mate.team.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import vip.mate.common.result.R;
import vip.mate.config.JacksonConfig;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.service.TeamRunApplicationService;
import vip.mate.team.service.TeamRunService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeamRunControllerTest {

    private static final Long RUN_ID = 9007199254740993L;
    private static final Long TEAM_ID = 9007199254740995L;
    private static final Long TASK_ID = 9007199254740997L;
    private static final Long BLOCKER_ID = 9007199254740999L;
    private static final Long WORKSPACE_ID = 30L;
    private static final String CONVERSATION_ID = "lead-conversation";

    private TeamRunService runService;
    private TeamRunApplicationService applicationService;
    private TeamRunController controller;

    @BeforeEach
    void setUp() {
        runService = mock(TeamRunService.class);
        applicationService = mock(TeamRunApplicationService.class);
        controller = new TeamRunController(runService, applicationService);
    }

    @Test
    void detailAndListsAreScopedToTheRequestedWorkspace() {
        TeamRunView view = view();
        TeamRunService.RunPage page = new TeamRunService.RunPage(List.of(view), null);
        when(runService.getRun(RUN_ID, WORKSPACE_ID)).thenReturn(view);
        when(runService.pageTeamRuns(TEAM_ID, WORKSPACE_ID, false, null, 20)).thenReturn(page);
        when(runService.pageConversationRuns(CONVERSATION_ID, WORKSPACE_ID, null, 20))
                .thenReturn(page);
        when(runService.listTeamRuns(TEAM_ID, WORKSPACE_ID, false)).thenReturn(List.of(view));
        when(runService.listConversationRuns(CONVERSATION_ID, WORKSPACE_ID)).thenReturn(List.of(view));

        assertEquals(view, controller.get(RUN_ID, WORKSPACE_ID).getData());
        assertEquals(List.of(view), controller.listTeamRuns(TEAM_ID, false, WORKSPACE_ID).getData());
        assertEquals(List.of(view), controller.listConversationRuns(CONVERSATION_ID, WORKSPACE_ID).getData());
        assertEquals(page, controller.pageTeamRuns(TEAM_ID, false, null, 20, WORKSPACE_ID).getData());
        assertEquals(page, controller.pageConversationRuns(
                CONVERSATION_ID, null, 20, WORKSPACE_ID).getData());
    }

    @Test
    void crossWorkspaceReadReturnsAReadableFailure() {
        when(runService.getRun(RUN_ID, WORKSPACE_ID))
                .thenThrow(new IllegalArgumentException("team run not found in workspace: " + RUN_ID));

        R<TeamRunView> result = controller.get(RUN_ID, WORKSPACE_ID);

        assertEquals(500, result.getCode());
        assertEquals("team run not found in workspace: " + RUN_ID, result.getMsg());
    }

    @Test
    void cancelDelegatesToTheApplicationServiceInTheCurrentWorkspace() {
        TeamRunView view = view();
        when(applicationService.cancelRun(RUN_ID, WORKSPACE_ID, "stop")).thenReturn(view);
        TeamRunController.CancelRunRequest request = new TeamRunController.CancelRunRequest();
        request.setReason("stop");

        assertEquals(view, controller.cancel(RUN_ID, request, WORKSPACE_ID).getData());

        verify(applicationService).cancelRun(RUN_ID, WORKSPACE_ID, "stop");
        verify(runService, never()).cancelRun(RUN_ID, WORKSPACE_ID, "stop");
    }

    @Test
    void endpointsDeclareExactPathsAndRoles() throws Exception {
        assertEndpoint("get", "viewer", "/team-runs/{runId}", Long.class, Long.class);
        assertEndpoint("listTeamRuns", "viewer", "/teams/{teamId}/runs",
                Long.class, boolean.class, Long.class);
        assertEndpoint("listConversationRuns", "viewer", "/conversations/{conversationId}/team-runs",
                String.class, Long.class);
        assertEndpoint("pageTeamRuns", "viewer", "/teams/{teamId}/runs/page",
                Long.class, boolean.class, String.class, int.class, Long.class);
        assertEndpoint("pageConversationRuns", "viewer", "/conversations/{conversationId}/team-runs/page",
                String.class, String.class, int.class, Long.class);
        assertEndpoint("cancel", "admin", "/team-runs/{runId}/cancel",
                Long.class, TeamRunController.CancelRunRequest.class, Long.class);
    }

    @Test
    void configuredJsonSerializesRunAndTeamLongIdsAsStrings() throws Exception {
        TeamRunView view = view();
        when(runService.getRun(RUN_ID, WORKSPACE_ID)).thenReturn(view);
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        mvc.perform(get("/api/v1/team-runs/{runId}", RUN_ID)
                        .header("X-Workspace-Id", WORKSPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(RUN_ID)))
                .andExpect(jsonPath("$.data.teamId").value(String.valueOf(TEAM_ID)))
                .andExpect(jsonPath("$.data.tasks[0].id").value(String.valueOf(TASK_ID)))
                .andExpect(jsonPath("$.data.tasks[0].blockedBy")
                        .value("[\"" + BLOCKER_ID + "\"]"))
                .andExpect(jsonPath("$.data.tasks[0].metadata")
                        .value("{\"planId\":\"" + RUN_ID + "\"}"));
    }

    private static TeamRunView view() {
        return new TeamRunView(RUN_ID, TEAM_ID, WORKSPACE_ID, 1L, CONVERSATION_ID,
                null, "Run", "Objective", "running", null, null, null,
                null, null, null, null,
                new TeamRunView.Progress(1, 0, 0, 0, 0), List.of(task()));
    }

    private static TeamRunView.Task task() {
        return new TeamRunView.Task(TASK_ID, TEAM_ID, RUN_ID, 1, "Task", null,
                "blocked", 0, "general", 1L, null,
                "[\"" + BLOCKER_ID + "\"]", false, null, null,
                null, null, null, "{\"planId\":\"" + RUN_ID + "\"}", null, null);
    }

    private static void assertEndpoint(String method, String role, String path,
                                       Class<?>... parameterTypes) throws Exception {
        var reflected = TeamRunController.class.getDeclaredMethod(method, parameterTypes);
        RequireWorkspaceRole permission = reflected.getAnnotation(RequireWorkspaceRole.class);
        assertNotNull(permission);
        assertEquals(role, permission.value());
        var get = reflected.getAnnotation(GetMapping.class);
        var post = reflected.getAnnotation(PostMapping.class);
        String actual = get != null ? get.value()[0] : post.value()[0];
        assertEquals(path, actual);
    }
}
