-- V171: Make mate_workspace_artifact.download_url nullable. See h2/V171.

ALTER TABLE mate_workspace_artifact MODIFY download_url VARCHAR(512) NULL;
