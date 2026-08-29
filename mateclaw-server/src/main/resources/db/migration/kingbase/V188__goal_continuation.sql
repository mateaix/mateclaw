-- Existing goals retain legacy behavior; new goals opt in through GoalService.
ALTER TABLE mate_agent_goal ADD COLUMN persistent_execution BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE mate_goal_continuation (
    goal_id BIGINT PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    next_run_at TIMESTAMP NOT NULL,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMP,
    failures INT NOT NULL DEFAULT 0,
    wake_requested BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(1000) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_goal_continuation_due ON mate_goal_continuation(state,next_run_at);
