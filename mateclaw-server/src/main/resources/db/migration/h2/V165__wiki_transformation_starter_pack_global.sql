-- V165: make the built-in starter-pack transformation templates global.
-- V108 seeded the 7 templates with a hardcoded workspace_id = 1, so any
-- workspace other than 1 saw an empty Transformations list. The fix marks them
-- global by clearing workspace_id (NULL = global). The column was NOT NULL, so
-- relax it first; listForKb / listByWorkspace / findByName treat NULL as global,
-- and the access checks already allow templates whose workspace_id IS NULL.
-- Targeted by fixed seed ids so real user templates are untouched.

ALTER TABLE mate_wiki_transformation ALTER COLUMN workspace_id SET NULL;

UPDATE mate_wiki_transformation
SET workspace_id = NULL
WHERE id IN (1000004001, 1000004002, 1000004003, 1000004004, 1000004005, 1000004006, 1000004007)
  AND workspace_id = 1;
