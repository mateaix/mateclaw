-- Bounded execution facts, independent of runtime recovery state.
CREATE TABLE mate_execution_attempt (
    id BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    runtime_kind VARCHAR(40) NOT NULL,
    runtime_session_id VARCHAR(128),
    invocation_key VARCHAR(191) NOT NULL,
    logical_call_id VARCHAR(191) NOT NULL,
    attempt_no INTEGER NOT NULL,
    provider_tool_call_id VARCHAR(191),
    tool_name VARCHAR(191) NOT NULL,
    goal_id BIGINT,
    goal_attempt_id VARCHAR(128),
    team_run_id BIGINT,
    team_task_id BIGINT,
    cron_run_id BIGINT,
    approval_id VARCHAR(128),
    owner_fence VARCHAR(191) NOT NULL,
    state VARCHAR(20) NOT NULL,
    effect_outcome VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    failure_reason VARCHAR(2048),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_execution_invocation UNIQUE(workspace_id, invocation_key),
    CONSTRAINT uk_execution_logical_attempt UNIQUE(workspace_id, logical_call_id, attempt_no)
);
CREATE INDEX idx_execution_conversation ON mate_execution_attempt(workspace_id, conversation_id, started_at, id);
CREATE INDEX idx_execution_state ON mate_execution_attempt(state, update_time);
CREATE INDEX idx_execution_goal ON mate_execution_attempt(workspace_id, goal_id);
CREATE TABLE mate_execution_evidence (
    id BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    source_key VARCHAR(191) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    result VARCHAR(20) NOT NULL,
    source_level VARCHAR(40) NOT NULL,
    scope_id BIGINT,
    generation BIGINT,
    input_fingerprint VARCHAR(128),
    recipe_id VARCHAR(191),
    recipe_revision BIGINT,
    check_scope VARCHAR(2048),
    artifact_ref VARCHAR(512),
    artifact_digest VARCHAR(128),
    summary VARCHAR(2048),
    payload_ref VARCHAR(512),
    observed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_execution_evidence_source UNIQUE(attempt_id, source_key),
    CONSTRAINT fk_execution_evidence_attempt FOREIGN KEY(attempt_id) REFERENCES mate_execution_attempt(id)
);
CREATE INDEX idx_evidence_workspace_observed ON mate_execution_evidence(workspace_id, observed_at, id);
CREATE TABLE mate_evidence_scope (
    id BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    resource_key VARCHAR(191) NOT NULL,
    host_id VARCHAR(191) NOT NULL,
    root_id VARCHAR(191) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    active_mutations INTEGER NOT NULL DEFAULT 0,
    tainted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_evidence_scope_resource UNIQUE(workspace_id, resource_key)
);
CREATE TABLE mate_goal_criterion_evidence (
    id BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    goal_id BIGINT NOT NULL,
    criterion_id VARCHAR(191) NOT NULL,
    criterion_revision BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_goal_criterion_evidence UNIQUE(goal_id, criterion_id, criterion_revision, evidence_id),
    CONSTRAINT fk_goal_criterion_evidence FOREIGN KEY(evidence_id) REFERENCES mate_execution_evidence(id)
);
CREATE INDEX idx_criterion_evidence_goal ON mate_goal_criterion_evidence(workspace_id, goal_id, criterion_id);
