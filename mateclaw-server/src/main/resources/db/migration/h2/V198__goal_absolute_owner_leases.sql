-- Unknown legacy lease timezones must not resurrect old owners on restart.
-- Zero expires existing leases; recovery retains their checkpoint/replay-safety decisions.
ALTER TABLE mate_goal_attempt ADD COLUMN lease_until_epoch_second BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mate_goal_continuation ADD COLUMN lease_until_epoch_second BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_goal_attempt_lease_epoch ON mate_goal_attempt(state, lease_until_epoch_second);
CREATE INDEX idx_goal_continuation_lease_epoch ON mate_goal_continuation(state, lease_until_epoch_second);
