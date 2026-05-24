-- V100: Add workspace_base_path column to mate_agent for Agent-level directory override.
-- When set, this overrides the workspace-level basePath for this Agent only.
ALTER TABLE mate_agent ADD COLUMN workspace_base_path VARCHAR(512) DEFAULT NULL;
