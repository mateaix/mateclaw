-- RFC-076: clean up invalid workspace_member rows produced by the legacy
-- WorkspaceSchemaMigration.ensureDefaultWorkspaceMembership() insert
-- (issue: https://github.com/matevip/mateclaw/issues/29).

-- 1) For users who already have a valid membership in another workspace,
--    drop their illegal default-workspace membership.
DELETE FROM mate_workspace_member
WHERE workspace_id = 1
  AND deleted = 0
  AND role NOT IN ('owner', 'admin', 'member', 'viewer')
  AND user_id IN (
      SELECT user_id FROM (
          SELECT user_id FROM mate_workspace_member
          WHERE workspace_id <> 1
            AND deleted = 0
            AND role IN ('owner', 'admin', 'member', 'viewer')
      ) t
  );

-- 2) For orphans whose only membership is the illegal default one,
--    normalize the role to 'member' so they don't get locked out entirely.
UPDATE mate_workspace_member
SET role = 'member', update_time = NOW()
WHERE workspace_id = 1
  AND deleted = 0
  AND role NOT IN ('owner', 'admin', 'member', 'viewer');
