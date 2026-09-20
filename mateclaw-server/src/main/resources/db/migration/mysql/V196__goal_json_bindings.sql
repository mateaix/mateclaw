-- Current trusted check per user requirement; invalidated by any referenced revision change.
CREATE TABLE mate_goal_json_binding (
    goal_id BIGINT NOT NULL,
    criterion_key VARCHAR(64) NOT NULL,
    requirement_revision BIGINT NOT NULL,
    evaluation_revision BIGINT NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    recipe_revision INTEGER NOT NULL,
    check_status VARCHAR(32) NOT NULL,
    checked_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (goal_id, criterion_key)
);
