package vip.mate.agent.template;

import static vip.mate.agent.template.CustomTemplateSnapshot.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** SQL is intentionally explicit: secret-bearing config_json never enters a snapshot. */
@Repository
@RequiredArgsConstructor
public class CustomTemplateRepository {
    private final JdbcTemplate db;
    private static final String SUMMARY =
            "id,name,description,icon,agent_type,creator_user_id,create_time,schema_version";

    private CustomTemplateService.Summary summary(java.sql.ResultSet r)
            throws java.sql.SQLException {
        return new CustomTemplateService.Summary(
                r.getString("id"),
                r.getString("name"),
                r.getString("description"),
                r.getString("icon"),
                r.getString("agent_type"),
                r.getLong("creator_user_id"),
                r.getTimestamp("create_time").toLocalDateTime(),
                r.getInt("schema_version"),
                false);
    }

    public List<CustomTemplateService.Summary> list(Long ws) {
        return db.query(
                "SELECT "
                        + SUMMARY
                        + " FROM mate_custom_agent_template WHERE workspace_id=? ORDER BY"
                        + " create_time DESC,id",
                (r, n) -> summary(r),
                ws);
    }

    public CustomTemplateService.Summary find(Long ws, String id) {
        return db
                .query(
                        "SELECT "
                                + SUMMARY
                                + " FROM mate_custom_agent_template WHERE workspace_id=? AND id=?",
                        (r, n) -> summary(r),
                        ws,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public String snapshot(Long ws, String id) {
        return db.queryForObject(
                "SELECT snapshot_json FROM mate_custom_agent_template WHERE workspace_id=? AND"
                        + " id=?",
                String.class,
                ws,
                id);
    }

    public void insert(Long ws, CustomTemplateService.Summary s, String json) {
        db.update(
                "INSERT INTO"
                    + " mate_custom_agent_template(id,workspace_id,name,name_key,description,icon,agent_type,creator_user_id,create_time,schema_version,snapshot_json)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                s.id(),
                ws,
                s.name(),
                s.name().toLowerCase(Locale.ROOT),
                s.description(),
                s.icon(),
                s.agentType(),
                s.creatorUserId(),
                s.createTime(),
                s.schemaVersion(),
                json);
    }

    public void delete(Long ws, String id) {
        db.update("DELETE FROM mate_custom_agent_template WHERE workspace_id=? AND id=?", ws, id);
    }

    public boolean templateNameExists(Long ws, String name) {
        return db.queryForObject(
                        "SELECT count(*) FROM mate_custom_agent_template WHERE workspace_id=? AND"
                                + " LOWER(name)=LOWER(?)",
                        Long.class,
                        ws,
                        name)
                > 0;
    }

    public boolean agentNameExists(Long ws, String name) {
        return db.queryForObject(
                        "SELECT count(*) FROM mate_agent WHERE workspace_id=? AND"
                                + " LOWER(name)=LOWER(?)",
                        Long.class,
                        ws,
                        name)
                > 0;
    }

    // Binding services ignore the retired deleted column; retain those raw row semantics.
    public List<Skill> skills(Long id) {
        return db.query(
                "SELECT skill_id,enabled FROM mate_agent_skill WHERE agent_id=? ORDER" + " BY id",
                (r, n) -> new Skill(r.getLong(1), r.getObject(2, Boolean.class)),
                id);
    }

    public List<Tool> tools(Long id) {
        return db.query(
                "SELECT tool_name,enabled FROM mate_agent_tool WHERE agent_id=? ORDER" + " BY id",
                (r, n) -> new Tool(r.getString(1), r.getObject(2, Boolean.class)),
                id);
    }

    public List<KnowledgeBase> kbs(Long id) {
        return db.query(
                "SELECT kb_id,enabled FROM mate_agent_wiki_kb WHERE agent_id=? ORDER" + " BY id",
                (r, n) -> new KnowledgeBase(r.getLong(1), r.getObject(2, Boolean.class)),
                id);
    }

    public List<Provider> providers(Long id) {
        return db.query(
                "SELECT provider_id,model_id,sort_order,enabled FROM mate_agent_provider_preference"
                        + " WHERE agent_id=? ORDER BY sort_order,id",
                (r, n) ->
                        new Provider(
                                r.getString(1),
                                r.getObject(2, Long.class),
                                r.getObject(3, Integer.class),
                                r.getObject(4, Boolean.class)),
                id);
    }

    public List<Document> documents(Long id, String name) {
        return db.query(
                "SELECT filename,content,enabled,sort_order FROM mate_workspace_file WHERE"
                    + " agent_id=? AND filename=? AND deleted=0 AND scope IN ('TEAM','GLOBAL') AND"
                    + " (owner_key IS NULL OR owner_key='') ORDER BY id",
                (r, n) ->
                        new Document(
                                r.getString(1),
                                r.getString(2),
                                r.getObject(3, Boolean.class),
                                r.getObject(4, Integer.class)),
                id,
                name);
    }

    public boolean skillAvailable(Long ws, Long id) {
        return db.queryForObject(
                        "SELECT count(*) FROM mate_skill WHERE id=? AND (workspace_id=? OR builtin=true) AND"
                                + " enabled=true AND deleted=0",
                        Long.class,
                        id,
                        ws)
                > 0;
    }

    public boolean kbAvailable(Long ws, Long id) {
        return db.queryForObject(
                        "SELECT count(*) FROM mate_wiki_knowledge_base WHERE id=? AND"
                            + " workspace_id=? AND deleted=0 AND status IN ('active','processing')",
                        Long.class,
                        id,
                        ws)
                > 0;
    }

    public boolean modelAvailable(String name) {
        return db.queryForObject(
                        "SELECT count(*) FROM mate_model_config m WHERE m.model_name=? AND m.enabled=true"
                                + " AND m.deleted=0 AND EXISTS (SELECT 1 FROM mate_model_provider p"
                                + " WHERE p.provider_id=m.provider AND p.enabled=true)",
                        Long.class,
                        name)
                > 0;
    }

    public boolean providerAvailable(Provider p) {
        if (db.queryForObject(
                        "SELECT count(*) FROM mate_model_provider WHERE provider_id=? AND enabled=true",
                        Long.class,
                        p.providerId())
                == 0) return false;
        return p.modelId() == null
                || db.queryForObject(
                                "SELECT count(*) FROM mate_model_config WHERE id=? AND provider=?"
                                        + " AND enabled=true AND deleted=0",
                                Long.class,
                                p.modelId(),
                                p.providerId())
                        > 0;
    }

    public void restore(Long id, CustomTemplateSnapshot s) {
        // Delete first, including zero-row states, so defaults/listeners can never widen
        // inheritance semantics.
        for (String table :
                List.of(
                        "mate_agent_skill",
                        "mate_agent_tool",
                        "mate_agent_wiki_kb",
                        "mate_agent_provider_preference",
                        "mate_workspace_file"))
            db.update("DELETE FROM " + table + " WHERE agent_id=?", id);
        LocalDateTime now = LocalDateTime.now();
        for (Skill b : s.skills())
            db.update(
                    "INSERT INTO"
                        + " mate_agent_skill(id,agent_id,skill_id,enabled,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,0)",
                    IdWorker.getId(),
                    id,
                    b.skillId(),
                    b.enabled(),
                    now,
                    now);
        for (Tool b : s.tools())
            db.update(
                    "INSERT INTO"
                        + " mate_agent_tool(id,agent_id,tool_name,enabled,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,0)",
                    IdWorker.getId(),
                    id,
                    b.toolName(),
                    b.enabled(),
                    now,
                    now);
        for (KnowledgeBase b : s.knowledgeBases())
            db.update(
                    "INSERT INTO"
                        + " mate_agent_wiki_kb(id,agent_id,kb_id,enabled,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,0)",
                    IdWorker.getId(),
                    id,
                    b.kbId(),
                    b.enabled(),
                    now,
                    now);
        for (Provider b : s.providers())
            db.update(
                    "INSERT INTO"
                        + " mate_agent_provider_preference(id,agent_id,provider_id,model_id,sort_order,enabled,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,?,?,0)",
                    IdWorker.getId(),
                    id,
                    b.providerId(),
                    b.modelId(),
                    b.sortOrder(),
                    b.enabled(),
                    now,
                    now);
        for (Document d : s.documents())
            db.update(
                    "INSERT INTO"
                        + " mate_workspace_file(id,agent_id,filename,content,file_size,enabled,sort_order,owner_key,scope,create_time,update_time,deleted)"
                        + " VALUES(?,?,?,?,?,?,?,'','TEAM',?,?,0)",
                    IdWorker.getId(),
                    id,
                    d.filename(),
                    d.content(),
                    d.content() == null ? 0 : d.content().getBytes(StandardCharsets.UTF_8).length,
                    d.enabled(),
                    d.sortOrder(),
                    now,
                    now);
        db.update("UPDATE mate_agent SET enabled=? WHERE id=?", s.metadata().enabled(), id);
    }
}
