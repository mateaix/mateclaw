-- V125: Add workspace_base_path column to mate_agent for Agent-level directory override.
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS workspace_base_path VARCHAR(512) DEFAULT NULL;
