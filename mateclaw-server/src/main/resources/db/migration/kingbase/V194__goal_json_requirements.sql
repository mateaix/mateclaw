-- Explicit opt-in is durable and cannot silently fall back to text completion.
ALTER TABLE mate_agent_goal ADD COLUMN json_acceptance_required BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE mate_goal_json_requirement (
    goal_id BIGINT NOT NULL,
    criterion_key VARCHAR(64) NOT NULL,
    artifact_slot VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    required_fields TEXT NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (goal_id, criterion_key)
);
