-- Application-managed append-only content; slot pointers advance under the goal lock.
CREATE TABLE mate_goal_json_artifact (
    artifact_id VARCHAR(36) NOT NULL PRIMARY KEY,
    goal_id BIGINT NOT NULL,
    artifact_slot VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    json_body MEDIUMTEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_length INTEGER NOT NULL,
    producer_kind VARCHAR(32) NOT NULL,
    producer_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    UNIQUE (goal_id, artifact_slot, generation)
);
CREATE TABLE mate_goal_json_slot (
    goal_id BIGINT NOT NULL,
    artifact_slot VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (goal_id, artifact_slot)
);
