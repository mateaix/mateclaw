-- Fix the ckjia-shopping MCP seed to its real production endpoint.
--
-- V85 seeded a dev/test placeholder (sse + http://localhost:8085/sse +
-- "Bearer ${CKJIA_MCP_KEY}"), which can never connect out of the box, so the
-- 参考价 / price-comparison skill stayed unusable until an admin hand-edited it.
-- The official CKJIA SaaS endpoint is Streamable HTTP at
-- https://m.ckjia.com/api/ai/mcp and needs no Authorization header.
--
-- Also raises both timeouts to 60s: the price-aggregation round-trip
-- (multi-platform search) legitimately runs longer than the old 30s ceiling.
--
-- SAFETY: only rewrites rows that still carry the untouched dev placeholder
-- URL, so an admin who already pointed ckjia-shopping at a private CKJIA
-- deployment (or the SaaS URL) is left completely alone. Idempotent — after it
-- runs the URL no longer matches the WHERE clause. `enabled` is deliberately
-- not changed: the server stays opt-in.
UPDATE mate_mcp_server
SET transport               = 'streamable_http',
    url                     = 'https://m.ckjia.com/api/ai/mcp',
    headers_json            = NULL,
    connect_timeout_seconds = 60,
    read_timeout_seconds    = 60,
    last_status             = 'disconnected',
    last_error              = NULL,
    description             = 'CKJIA price comparison MCP server (Streamable HTTP). Disabled by default — enable it in Settings > MCP Connections to use the 参考价 shopping skill.',
    update_time             = NOW()
WHERE name = 'ckjia-shopping'
  AND url = 'http://localhost:8085/sse';
