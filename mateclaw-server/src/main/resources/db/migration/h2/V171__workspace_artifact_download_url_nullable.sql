-- V171: Make mate_workspace_artifact.download_url nullable.
-- The downloadUrl is now built at read time (in WorkspaceArtifactService.toVO)
-- from the artifact id, so the column is no longer populated at insert. The
-- original V170 migration declared it NOT NULL with no default, which silently
-- broke every registration (the NOT_NULL insert strategy omits the null field,
-- so the DB raised a constraint violation caught + swallowed by the
-- best-effort register() path). Making it nullable aligns the schema with the
-- read-time-URL design.

ALTER TABLE mate_workspace_artifact ALTER COLUMN download_url DROP NOT NULL;
