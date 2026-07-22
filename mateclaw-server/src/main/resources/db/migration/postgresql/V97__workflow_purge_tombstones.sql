-- See the matching H2 file for context. The workflow / trigger entities
-- moved off @TableLogic to align with the project's hard-delete convention;
-- this migration drops any tombstones the old soft-delete path persisted so
-- list endpoints don't expose them after the annotation-driven filter is
-- removed.

DELETE FROM mate_workflow WHERE deleted <> 0;
DELETE FROM mate_workflow_run WHERE deleted <> 0;
DELETE FROM mate_trigger WHERE deleted <> 0;
