-- V125: Add workspace_base_path column to mate_agent for agent-level directory override.
-- When set, this overrides the workspace-level basePath for this agent only.
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS workspace_base_path VARCHAR(512) DEFAULT NULL;
