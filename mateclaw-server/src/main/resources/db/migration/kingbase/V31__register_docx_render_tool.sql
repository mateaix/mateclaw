-- V31: Register DocxRenderTool as built-in tool (RFC-045)
-- Idempotent: ON DUPLICATE KEY UPDATE keeps the row in sync if it already exists.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000019, 'DocxRenderTool', 'DOCX Render', 'Render Markdown directly into a .docx and return a one-time download link. In-process Apache POI implementation, no Node.js subprocess; supports headings, bold, lists, tables. Preferred tool for creating new documents.', 'builtin', 'docxRenderTool', '📝', 1, 1, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, update_time=EXCLUDED.update_time;
