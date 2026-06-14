-- Seed ckjia-shopping MCP server config (disabled by default).
-- The localhost URL below is a dev/test placeholder only. Production admins
-- must replace it in Settings > MCP Connections with the official CKJIA SaaS
-- domain or their private CKJIA deployment URL before enabling the server,
-- then configure CKJIA_MCP_KEY for authorization.
--
-- Column name is url (not endpoint) per McpServerEntity.
-- headers_json uses ${CKJIA_MCP_KEY} placeholder so the plaintext API key
-- never lands in the database (parseHeaders expands env vars at request time).

-- mate_mcp_server.id is BIGINT NOT NULL PRIMARY KEY without DB-side auto-increment;
-- production uses MyBatis Plus Snowflake at insert time, but Flyway bypasses that.
-- Following existing seed convention (1000000901=filesystem, 1000000902=github),
-- ckjia-shopping takes 1000000903.
INSERT INTO mate_mcp_server (
    id, name, transport, url, headers_json, enabled, description,
    connect_timeout_seconds, read_timeout_seconds, builtin, create_time, update_time, deleted
)
SELECT 1000000903,
       'ckjia-shopping',
       'sse',
       'http://localhost:8085/sse',
       '{"Authorization": "Bearer ${CKJIA_MCP_KEY}"}',
       FALSE,
       'CKJIA price comparison MCP server. Disabled by default; replace the dev/test localhost URL with the production CKJIA domain before enabling.',
       30, 30, TRUE,
       NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM mate_mcp_server WHERE name = 'ckjia-shopping');
