ALTER TABLE mate_goal_continuation ADD COLUMN current_attempt_id VARCHAR(36);
ALTER TABLE mate_goal_continuation ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE mate_goal_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY,
    goal_id BIGINT NOT NULL,
    conversation_id VARCHAR(160) NOT NULL,
    parent_attempt_id VARCHAR(36),
    trigger_type VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    lease_token VARCHAR(64) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    input_item_id BIGINT,
    assistant_message_id BIGINT,
    replay_safety VARCHAR(16) NOT NULL DEFAULT 'safe',
    checkpoint_type VARCHAR(32) NOT NULL DEFAULT 'claimed',
    finish_reason VARCHAR(128),
    error_category VARCHAR(128),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_goal_attempt_goal_created(goal_id, created_at),
    INDEX idx_goal_attempt_expired(state, lease_until)
);

CREATE TABLE mate_conversation_input_queue (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(160) NOT NULL,
    agent_id BIGINT,
    created_by VARCHAR(100) NOT NULL,
    message LONGTEXT NOT NULL,
    content_parts LONGTEXT,
    state VARCHAR(16) NOT NULL,
    claimed_by_attempt_id VARCHAR(36),
    persisted_message_id BIGINT,
    cancel_reason VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_conversation_input_due(conversation_id, state, id)
);
