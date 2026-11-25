package vip.mate.agent.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.tool.service.AvailableToolService;

import java.util.*;

class CustomTemplateIntegrationTest {
    private final vip.mate.skill.mcp.McpSkillBridge mcpSkills = mock(vip.mate.skill.mcp.McpSkillBridge.class);
    private final vip.mate.skill.acp.AcpSkillBridge acpSkills = mock(vip.mate.skill.acp.AcpSkillBridge.class);
    private final vip.mate.tool.mcp.runtime.McpWorkspaceAccess mcpAccess = mock(vip.mate.tool.mcp.runtime.McpWorkspaceAccess.class);

    JdbcTemplate db;
    CustomTemplateService service;
    AgentEntity source;
    TransactionTemplate tx;
    AgentService agents;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/h2/V206__custom_employee_templates.sql"))
                .execute(ds);
        db.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY,name VARCHAR(255),workspace_id"
                        + " BIGINT,enabled BOOLEAN)");
        db.execute(
                "CREATE TABLE mate_agent_skill(id BIGINT PRIMARY KEY,agent_id BIGINT,skill_id"
                    + " BIGINT,enabled BOOLEAN,config_json CLOB,deleted INT DEFAULT 0,create_time"
                    + " TIMESTAMP,update_time TIMESTAMP)");
        db.execute(
                "CREATE TABLE mate_agent_tool(id BIGINT PRIMARY KEY,agent_id BIGINT,tool_name"
                        + " VARCHAR(255),enabled BOOLEAN,deleted INT DEFAULT 0,create_time"
                        + " TIMESTAMP,update_time TIMESTAMP)");
        db.execute(
                "CREATE TABLE mate_agent_wiki_kb(id BIGINT PRIMARY KEY,agent_id BIGINT,kb_id"
                        + " BIGINT,enabled BOOLEAN,deleted INT DEFAULT 0,create_time"
                        + " TIMESTAMP,update_time TIMESTAMP)");
        db.execute(
                "CREATE TABLE mate_agent_provider_preference(id BIGINT PRIMARY KEY,agent_id"
                        + " BIGINT,provider_id VARCHAR(128),model_id BIGINT,sort_order INT,enabled"
                        + " BOOLEAN,deleted INT DEFAULT 0,create_time TIMESTAMP,update_time"
                        + " TIMESTAMP)");
        db.execute(
                "CREATE TABLE mate_workspace_file(id BIGINT PRIMARY KEY,agent_id BIGINT,filename"
                        + " VARCHAR(255),content CLOB,file_size BIGINT,enabled BOOLEAN,sort_order"
                        + " INT,owner_key VARCHAR(255),scope VARCHAR(32),deleted INT DEFAULT"
                        + " 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute(
                "CREATE TABLE mate_skill(id BIGINT PRIMARY KEY,workspace_id BIGINT,enabled"
                        + " BOOLEAN,deleted INT DEFAULT 0,builtin BOOLEAN DEFAULT false)");
        db.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY,workspace_id"
                        + " BIGINT,status VARCHAR(32),deleted INT DEFAULT 0)");
        db.execute(
                "CREATE TABLE mate_model_config(id BIGINT PRIMARY KEY,provider"
                    + " VARCHAR(128),model_name VARCHAR(255),enabled BOOLEAN,deleted INT DEFAULT"
                    + " 0)");
        db.execute("CREATE TABLE mate_model_provider(provider_id VARCHAR(128) PRIMARY KEY,enabled BOOLEAN DEFAULT true)");
        source = new AgentEntity();
        source.setId(1L);
        source.setWorkspaceId(10L);
        source.setName("Source");
        source.setRuntimeType("native");
        source.setRuntimeConfig("secret-runtime");
        source.setWorkspaceBasePath("/host/private");
        source.setEnabled(false);
        source.setSkillsDisabled(false);
        source.setToolsDisabled(true);
        source.setWikiDisabled(false);
        source.setModelName("model");
        source.setDefaultThinkingLevel("high");
        source.setAgentType("react");
        agents = mock(AgentService.class);
        when(agents.getAgent(1L)).thenReturn(source);
        when(agents.createAgent(any()))
                .thenAnswer(
                        inv -> {
                            AgentEntity a = inv.getArgument(0);
                            a.setId(2L);
                            a.setEnabled(true);
                            db.update(
                                    "INSERT INTO mate_agent VALUES(?,?,?,?)",
                                    a.getId(),
                                    a.getName(),
                                    a.getWorkspaceId(),
                                    a.getEnabled());
                            return a;
                        });
        AvailableToolService tools = mock(AvailableToolService.class);
        when(tools.listAvailable(10L)).thenReturn(List.of());
        service =
                new CustomTemplateService(
                        new CustomTemplateRepository(db),
                        agents,
                        tools,
                        new ObjectMapper(),
                        mock(vip.mate.i18n.I18nService.class), mcpSkills, acpSkills, mcpAccess);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.update("INSERT INTO mate_model_config VALUES(4,'provider','model',true,0)");
        db.update("INSERT INTO mate_model_provider(provider_id) VALUES('provider')");
    }

    CustomTemplateService.Summary save(List<String> files) {
        return tx.execute(
                s ->
                        service.save(
                                10L,
                                7L,
                                new CustomTemplateService.SaveRequest(
                                        1L, "Reusable", "description", files)));
    }

    AgentEntity apply(String id) {
        return tx.execute(
                s ->
                        service.apply(
                                10L, 7L, id, new CustomTemplateService.ApplyRequest("New hire")));
    }

    @Test
    void immutableSnapshotRoundTripsFlagsDisabledRowsPreferencesAndSharedFilesWithoutSecrets() {
        db.update("INSERT INTO mate_skill(id,workspace_id,enabled,deleted) VALUES(5,10,true,0)");
        db.update(
                "INSERT INTO mate_agent_skill(id,agent_id,skill_id,enabled,config_json)"
                        + " VALUES(11,1,5,false,'credential-secret')");
        db.update(
                "INSERT INTO"
                    + " mate_agent_provider_preference(id,agent_id,provider_id,model_id,sort_order,enabled)"
                    + " VALUES(12,1,'provider',4,8,false)");
        db.update(
                "INSERT INTO"
                    + " mate_workspace_file(id,agent_id,filename,content,enabled,sort_order,owner_key,scope)"
                    + " VALUES(13,1,'SOUL.md','safe',true,9,'','TEAM')");
        db.update(
                "INSERT INTO"
                    + " mate_workspace_file(id,agent_id,filename,content,enabled,sort_order,owner_key,scope)"
                    + " VALUES(14,1,'SOUL.md','private-memory',true,0,'user:9','PERSONAL')");
        db.update("UPDATE mate_agent_skill SET deleted=1 WHERE agent_id=1");
        var template = save(List.of("SOUL.md"));
        String json =
                db.queryForObject(
                        "SELECT snapshot_json FROM mate_custom_agent_template", String.class);
        for (String secret :
                List.of(
                        "secret-runtime",
                        "/host/private",
                        "credential-secret",
                        "private-memory",
                        "configJson",
                        "runtimeConfig")) assertFalse(json.contains(secret), secret);
        source.setDefaultThinkingLevel("off");
        db.update("UPDATE mate_workspace_file SET content='changed'");
        AgentEntity hired = apply(template.id());
        assertEquals("high", hired.getDefaultThinkingLevel());
        assertEquals("native", hired.getRuntimeType());
        assertNull(hired.getRuntimeConfig());
        assertNull(hired.getWorkspaceBasePath());
        assertEquals(false, hired.getEnabled());
        assertEquals(
                false,
                db.queryForObject("SELECT enabled FROM mate_agent WHERE id=2", Boolean.class));
        assertEquals(true, hired.getToolsDisabled());
        assertEquals(
                false,
                db.queryForObject(
                        "SELECT enabled FROM mate_agent_skill WHERE agent_id=2", Boolean.class));
        assertNull(
                db.queryForObject(
                        "SELECT config_json FROM mate_agent_skill WHERE agent_id=2", String.class));
        assertEquals(
                false,
                db.queryForObject(
                        "SELECT enabled FROM mate_agent_provider_preference WHERE agent_id=2",
                        Boolean.class));
        assertEquals(
                "safe",
                db.queryForObject(
                        "SELECT content FROM mate_workspace_file WHERE agent_id=2", String.class));
        assertEquals(
                9,
                db.queryForObject(
                        "SELECT sort_order FROM mate_workspace_file WHERE agent_id=2",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mate_agent_tool WHERE agent_id=2", Integer.class));
    }

    @Test
    void scopesListSaveApplyDeleteAndOnlyCreatorOrAdminCanDelete() {
        var t = save(List.of());
        assertEquals(1, service.list(10L).size());
        assertTrue(service.list(99L).isEmpty());
        assertThrows(
                MateClawException.class,
                () ->
                        service.save(
                                99L,
                                7L,
                                new CustomTemplateService.SaveRequest(1L, "x", null, List.of())));
        assertThrows(
                MateClawException.class,
                () -> service.apply(99L, 7L, t.id(), new CustomTemplateService.ApplyRequest("x")));
        assertThrows(MateClawException.class, () -> service.delete(10L, 8L, t.id(), false));
        assertThrows(MateClawException.class, () -> service.delete(99L, 7L, t.id(), true));
        service.delete(10L, 8L, t.id(), true);
        assertTrue(service.list(10L).isEmpty());
    }

    @Test
    void excludesMemoryOwnerDocumentsAndRejectsDshRatherThanConvertingRuntime() {
        for (String file :
                List.of("MEMORY.md", "PROFILE.md", "USER.md", "daily/2026-11-24.md", "../SOUL.md"))
            assertThrows(MateClawException.class, () -> save(List.of(file)));
        db.update(
                "INSERT INTO"
                    + " mate_workspace_file(id,agent_id,filename,content,enabled,sort_order,owner_key,scope)"
                    + " VALUES(15,1,'SOUL.md','private',true,0,'user:9','PERSONAL')");
        assertThrows(MateClawException.class, () -> save(List.of("SOUL.md")));
        source.setRuntimeType("dsh");
        assertThrows(MateClawException.class, () -> save(List.of()));
    }

    @Test
    void missingOrDisabledDependenciesAbortBeforeCreatingAgent() {
        db.update("INSERT INTO mate_skill(id,workspace_id,enabled,deleted) VALUES(5,10,true,0)");
        db.update(
                "INSERT INTO mate_agent_skill(id,agent_id,skill_id,enabled) VALUES(11,1,5,false)");
        var t = save(List.of());
        db.update("UPDATE mate_skill SET enabled=false WHERE id=5");
        assertThrows(MateClawException.class, () -> apply(t.id()));
        verify(agents, never()).createAgent(any());
        db.update("UPDATE mate_skill SET enabled=true,workspace_id=99 WHERE id=5");
        assertThrows(MateClawException.class, () -> apply(t.id()));
    }

    @Test
    void duplicateNamesAreCleanErrorsAndFailureAfterCreationRollsBackWholeHire() {
        var t = save(List.of());
        assertThrows(MateClawException.class, () -> save(List.of()));
        db.update("INSERT INTO mate_agent VALUES(9,'New hire',10,true)");
        assertThrows(MateClawException.class, () -> apply(t.id()));
        db.update("DELETE FROM mate_agent");
        db.execute("DROP TABLE mate_agent_tool");
        assertThrows(RuntimeException.class, () -> apply(t.id()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mate_agent", Integer.class));
    }

    @Test
    void preservesAllDisabledToolAndKbRowsAndExplicitOptOuts() {
        source.setSkillsDisabled(true);
        source.setWikiDisabled(true);
        db.update("INSERT INTO mate_wiki_knowledge_base VALUES(6,10,'active',0)");
        db.update("INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled) VALUES(16,1,6,false)");
        db.update(
                "INSERT INTO mate_agent_tool(id,agent_id,tool_name,enabled)"
                        + " VALUES(17,1,'remember',false)");
        var tools = new vip.mate.tool.model.AvailableToolDTO();
        tools.setName("remember");
        tools.setAvailable(true);
        AvailableToolService picker = mock(AvailableToolService.class);
        when(picker.listAvailable(10L)).thenReturn(List.of(tools));
        service =
                new CustomTemplateService(
                        new CustomTemplateRepository(db),
                        agents,
                        picker,
                        new ObjectMapper(),
                        mock(vip.mate.i18n.I18nService.class), mcpSkills, acpSkills, mcpAccess);
        var template = save(List.of());
        AgentEntity hired = apply(template.id());
        assertEquals(true, hired.getSkillsDisabled());
        assertEquals(true, hired.getToolsDisabled());
        assertEquals(true, hired.getWikiDisabled());
        assertEquals(
                false,
                db.queryForObject(
                        "SELECT enabled FROM mate_agent_tool WHERE agent_id=2", Boolean.class));
        assertEquals(
                false,
                db.queryForObject(
                        "SELECT enabled FROM mate_agent_wiki_kb WHERE agent_id=2", Boolean.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mate_agent_skill WHERE agent_id=2", Integer.class));
    }

    @Test
    void rejectsMissingToolsKnowledgeBasesAndModelsWithoutPartialHire() {
        db.update(
                "INSERT INTO mate_agent_tool(id,agent_id,tool_name,enabled)"
                        + " VALUES(17,1,'unavailable',false)");
        var tools = save(List.of());
        assertThrows(MateClawException.class, () -> apply(tools.id()));
        db.update("DELETE FROM mate_custom_agent_template");
        db.update("DELETE FROM mate_agent_tool");
        source.setPrimaryKbId(6L);
        var kb = save(List.of());
        assertThrows(MateClawException.class, () -> apply(kb.id()));
        db.update("DELETE FROM mate_custom_agent_template");
        source.setPrimaryKbId(null);
        var model = save(List.of());
        db.update("UPDATE mate_model_config SET enabled=false");
        assertThrows(MateClawException.class, () -> apply(model.id()));
        verify(agents, never()).createAgent(any());
    }

    @Test
    void rejectsEmployeeNamesLongerThanDatabaseLimitBeforeCreation() {
        var template = save(List.of());
        assertThrows(
                MateClawException.class,
                () ->
                        service.apply(
                                10L,
                                7L,
                                template.id(),
                                new CustomTemplateService.ApplyRequest("x".repeat(129))));
        verify(agents, never()).createAgent(any());
    }
    @Test
    void globalBuiltinSkillCanBeReusedOutsideItsHomeWorkspace() {
        db.update("INSERT INTO mate_skill(id,workspace_id,enabled,deleted,builtin) VALUES(51,99,true,0,true)");
        db.update("INSERT INTO mate_agent_skill(id,agent_id,skill_id,enabled) VALUES(52,1,51,true)");
        var template = save(List.of());
        assertDoesNotThrow(() -> apply(template.id()));
        assertEquals(51L, db.queryForObject("SELECT skill_id FROM mate_agent_skill WHERE agent_id=2", Long.class));
    }

    @Test
    void disabledProviderOnlyPreferencePreventsHiringBeforeCreation() {
        source.setModelName(null);
        db.update("INSERT INTO mate_agent_provider_preference(id,agent_id,provider_id,enabled) VALUES(52,1,'provider',true)");
        var template = save(List.of());
        db.update("UPDATE mate_model_provider SET enabled=false WHERE provider_id='provider'");
        assertThrows(vip.mate.exception.MateClawException.class, () -> apply(template.id()));
        verify(agents, never()).createAgent(any());
    }

    @Test
    void availableVirtualMcpAndAcpSkillsRoundTripThroughTheirExistingBridges() {
        long mcp = vip.mate.skill.mcp.McpSkillBridge.VIRTUAL_ID_BASE | 21L;
        long acp = vip.mate.skill.acp.AcpSkillBridge.VIRTUAL_ID_BASE | 22L;
        db.update("INSERT INTO mate_agent_skill(id,agent_id,skill_id,enabled) VALUES(51,1,?,true),(52,1,?,false)", mcp, acp);
        var resolvedMcp = vip.mate.skill.runtime.model.ResolvedSkill.builder().id(mcp).enabled(true).runtimeAvailable(true).dependencyReady(true).workspaceId(10L).build();
        var resolvedAcp = vip.mate.skill.runtime.model.ResolvedSkill.builder().id(acp).enabled(true).runtimeAvailable(true).dependencyReady(true).workspaceId(10L).build();
        when(mcpSkills.findResolvedById(mcp)).thenReturn(resolvedMcp);
        when(acpSkills.findResolvedById(acp)).thenReturn(resolvedAcp);
        when(mcpAccess.canUse(21L, 10L)).thenReturn(true);
        var template = save(List.of());
        assertDoesNotThrow(() -> apply(template.id()));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM mate_agent_skill WHERE agent_id=2", Integer.class));
        assertEquals(false, db.queryForObject("SELECT enabled FROM mate_agent_skill WHERE agent_id=2 AND skill_id=?", Boolean.class, acp));
    }

    @Test
    void virtualSkillAccessCannotCrossWorkspaceOrUseDisabledBackends() {
        long mcp = vip.mate.skill.mcp.McpSkillBridge.VIRTUAL_ID_BASE | 21L;
        db.update("INSERT INTO mate_agent_skill(id,agent_id,skill_id,enabled) VALUES(51,1,?,true)", mcp);
        when(mcpSkills.findResolvedById(mcp)).thenReturn(vip.mate.skill.runtime.model.ResolvedSkill.builder().id(mcp).enabled(true).runtimeAvailable(true).dependencyReady(true).build());
        when(mcpAccess.canUse(21L, 10L)).thenReturn(false);
        var template = save(List.of());
        assertThrows(vip.mate.exception.MateClawException.class, () -> apply(template.id()));
        verify(agents, never()).createAgent(any());
    }

    @Test
    void disabledProviderBehindModelOverridePreventsHiring() {
        var template = save(List.of());
        db.update("UPDATE mate_model_provider SET enabled=false WHERE provider_id='provider'");
        assertThrows(vip.mate.exception.MateClawException.class, () -> apply(template.id()));
        verify(agents, never()).createAgent(any());
    }

}
