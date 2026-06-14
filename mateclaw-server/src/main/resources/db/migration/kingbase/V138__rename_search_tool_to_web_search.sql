-- Rename the built-in web-search tool from "search" to "web_search".
-- DashScope's native protocol reserves the function name "search" and rejects any
-- request that declares a tool with that name ("InvalidParameter: Tool names are not
-- allowed to be [search]"), which broke tool use for every qwen/DashScope-native model
-- that had this tool bound. Migrate existing agent bindings to the new name so they
-- keep resolving after the tool was renamed in code. Idempotent.
UPDATE mate_agent_tool SET tool_name = 'web_search' WHERE tool_name = 'search';
