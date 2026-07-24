-- V172: Agent team foundation — team registry, membership, and the shared task board.
-- A team groups one lead agent with member agents. The lead orchestrates work by
-- creating tasks on the shared board; tasks are dispatched to the assigned member,
-- executed in an isolated conversation, and completed with a result summary.
-- (H2 dialect)

CREATE TABLE IF NOT EXISTS mate_agent_team (
    id             BIGINT        NOT NULL PRIMARY KEY,
    name           VARCHAR(128)  NOT NULL,
    description    VARCHAR(1024),
    lead_agent_id  BIGINT        NOT NULL,
    status         VARCHAR(16)   DEFAULT 'active',
    -- Monotonic per-team counter backing human-readable task numbers (#1, #2, ...).
    task_seq       INT           DEFAULT 0,
    settings       TEXT,
    created_by     VARCHAR(64),
    create_time    TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT           DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_team_lead ON mate_agent_team(lead_agent_id);

CREATE TABLE IF NOT EXISTS mate_agent_team_member (
    id           BIGINT       NOT NULL PRIMARY KEY,
    team_id      BIGINT       NOT NULL,
    agent_id     BIGINT       NOT NULL,
    role         VARCHAR(16)  DEFAULT 'member',
    create_time  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      INT          DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_team_member_team ON mate_agent_team_member(team_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_team_member_agent ON mate_agent_team_member(agent_id);

CREATE TABLE IF NOT EXISTS mate_team_task (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    team_id               BIGINT        NOT NULL,
    task_number           INT,
    subject               VARCHAR(512)  NOT NULL,
    description           TEXT,
    status                VARCHAR(16)   DEFAULT 'pending',
    priority              INT           DEFAULT 0,
    task_type             VARCHAR(16)   DEFAULT 'general',
    -- Intended executor chosen at creation time (required); dispatch turns it into owner.
    assignee_agent_id     BIGINT        NULL,
    -- Agent currently executing the task; NULL until claimed or assigned.
    owner_agent_id        BIGINT        NULL,
    created_by_agent_id   BIGINT        NULL,
    -- JSON array of prerequisite task ids (as strings); task stays 'blocked' until all
    -- prerequisites reach a releasing status (completed / cancelled).
    blocked_by            TEXT,
    -- When TRUE, completion parks the task in 'in_review' until a human approves.
    require_approval      BOOLEAN       DEFAULT FALSE,
    progress_percent      INT           NULL,
    progress_step         VARCHAR(512),
    result                TEXT,
    reason                VARCHAR(1024),
    -- Dispatch attempts; the dispatcher auto-fails the task past the circuit-breaker cap.
    dispatch_count        INT           DEFAULT 0,
    -- Execution lease; an in_progress task whose lease expired is recoverable as stale.
    lock_expires_at       TIMESTAMP     NULL,
    conversation_id       VARCHAR(64),
    lead_conversation_id  VARCHAR(64),
    username              VARCHAR(64),
    channel               VARCHAR(32),
    metadata              TEXT,
    create_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT           DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_team_task_board ON mate_team_task(team_id, status);
CREATE INDEX IF NOT EXISTS idx_team_task_owner ON mate_team_task(team_id, owner_agent_id, status);
CREATE INDEX IF NOT EXISTS idx_team_task_number ON mate_team_task(team_id, task_number);

CREATE TABLE IF NOT EXISTS mate_team_task_comment (
    id            BIGINT       NOT NULL PRIMARY KEY,
    task_id       BIGINT       NOT NULL,
    team_id       BIGINT       NOT NULL,
    author_type   VARCHAR(16),
    author_id     VARCHAR(64),
    -- 'note' for regular comments; a 'blocker' comment auto-fails the task and
    -- escalates to the team lead.
    comment_type  VARCHAR(16)  DEFAULT 'note',
    content       TEXT,
    create_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_team_task_comment ON mate_team_task_comment(task_id, create_time);
