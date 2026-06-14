-- V49 (MySQL): see h2/V49 for full rationale.

UPDATE mate_tool SET enabled = TRUE
WHERE bean_name IN ('writeFileTool', 'editFileTool')
  AND enabled = FALSE;
