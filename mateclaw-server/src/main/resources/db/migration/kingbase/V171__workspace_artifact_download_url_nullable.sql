-- V171: Make mate_workspace_artifact.download_url nullable. See h2/V171.

ALTER TABLE mate_workspace_artifact ALTER COLUMN download_url DROP NOT NULL;
