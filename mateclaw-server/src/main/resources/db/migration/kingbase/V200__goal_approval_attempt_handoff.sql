-- Exact durable handoff from a settled approval to one newly fenced attempt.
-- Older waiting rows remain unbound; never guess the originating attempt.
ALTER TABLE mate_goal_continuation ADD COLUMN waiting_approval_attempt_id VARCHAR(36) NULL;
ALTER TABLE mate_goal_attempt ADD COLUMN approval_pending_id VARCHAR(64) NULL;
CREATE UNIQUE INDEX uq_goal_attempt_approval ON mate_goal_attempt(approval_pending_id);
