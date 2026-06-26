-- V20: Purge soft-deleted rows from all tables and retire soft-delete semantics.
-- @TableLogic has been removed from all entities — the project no longer
-- supports soft-delete. Clear residual deleted=1 rows so queries that still
-- reference the deleted column (or raw SQL in service layer) continue to
-- behave consistently. The deleted column itself is retained with its
-- NOT NULL DEFAULT 0 constraint for schema compatibility.
DELETE FROM mate_agent               WHERE deleted = 1;
DELETE FROM mate_agent_skill         WHERE deleted = 1;
DELETE FROM mate_agent_tool          WHERE deleted = 1;
DELETE FROM mate_channel             WHERE deleted = 1;
DELETE FROM mate_channel_session     WHERE deleted = 1;
DELETE FROM mate_conversation        WHERE deleted = 1;
DELETE FROM mate_cron_job            WHERE deleted = 1;
DELETE FROM mate_datasource          WHERE deleted = 1;
DELETE FROM mate_mcp_server          WHERE deleted = 1;
DELETE FROM mate_memory_recall       WHERE deleted = 1;
DELETE FROM mate_message             WHERE deleted = 1;
DELETE FROM mate_model_config        WHERE deleted = 1;
DELETE FROM mate_plan                WHERE deleted = 1;
DELETE FROM mate_plugin              WHERE deleted = 1;
DELETE FROM mate_skill               WHERE deleted = 1;
DELETE FROM mate_sub_plan            WHERE deleted = 1;
DELETE FROM mate_tool                WHERE deleted = 1;
DELETE FROM mate_tool_approval       WHERE deleted = 1;
DELETE FROM mate_tool_guard_audit_log WHERE deleted = 1;
DELETE FROM mate_tool_guard_rule     WHERE deleted = 1;
DELETE FROM mate_user                WHERE deleted = 1;
DELETE FROM mate_wiki_chunk          WHERE deleted = 1;
DELETE FROM mate_wiki_knowledge_base WHERE deleted = 1;
DELETE FROM mate_wiki_page           WHERE deleted = 1;
DELETE FROM mate_wiki_raw_material   WHERE deleted = 1;
DELETE FROM mate_workspace           WHERE deleted = 1;
DELETE FROM mate_workspace_file      WHERE deleted = 1;
DELETE FROM mate_workspace_member    WHERE deleted = 1;
