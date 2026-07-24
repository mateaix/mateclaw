-- V172: Agent team foundation — team registry, membership, and the shared task board.
-- (MySQL dialect). See h2/V172 for design notes.

CREATE TABLE IF NOT EXISTS mate_agent_team (
    id             BIGINT        NOT NULL PRIMARY KEY,
    name           VARCHAR(128)  NOT NULL,
    description    VARCHAR(1024),
    lead_agent_id  BIGINT        NOT NULL,
    status         VARCHAR(16)   DEFAULT 'active',
    task_seq       INT           DEFAULT 0,
    settings       TEXT,
    created_by     VARCHAR(64),
    create_time    TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        INT           DEFAULT 0,
    KEY idx_agent_team_lead (lead_agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_agent_team_member (
    id           BIGINT       NOT NULL PRIMARY KEY,
    team_id      BIGINT       NOT NULL,
    agent_id     BIGINT       NOT NULL,
    role         VARCHAR(16)  DEFAULT 'member',
    create_time  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      INT          DEFAULT 0,
    KEY idx_team_member_team (team_id, agent_id),
    KEY idx_team_member_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_team_task (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    team_id               BIGINT        NOT NULL,
    task_number           INT,
    subject               VARCHAR(512)  NOT NULL,
    description           TEXT,
    status                VARCHAR(16)   DEFAULT 'pending',
    priority              INT           DEFAULT 0,
    task_type             VARCHAR(16)   DEFAULT 'general',
    assignee_agent_id     BIGINT        NULL,
    owner_agent_id        BIGINT        NULL,
    created_by_agent_id   BIGINT        NULL,
    blocked_by            TEXT,
    require_approval      BOOLEAN       DEFAULT FALSE,
    progress_percent      INT           NULL,
    progress_step         VARCHAR(512),
    result                TEXT,
    reason                VARCHAR(1024),
    dispatch_count        INT           DEFAULT 0,
    lock_expires_at       TIMESTAMP     NULL,
    conversation_id       VARCHAR(64),
    lead_conversation_id  VARCHAR(64),
    username              VARCHAR(64),
    channel               VARCHAR(32),
    metadata              TEXT,
    create_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               INT           DEFAULT 0,
    KEY idx_team_task_board (team_id, status),
    KEY idx_team_task_owner (team_id, owner_agent_id, status),
    KEY idx_team_task_number (team_id, task_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_team_task_comment (
    id            BIGINT       NOT NULL PRIMARY KEY,
    task_id       BIGINT       NOT NULL,
    team_id       BIGINT       NOT NULL,
    author_type   VARCHAR(16),
    author_id     VARCHAR(64),
    comment_type  VARCHAR(16)  DEFAULT 'note',
    content       TEXT,
    create_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0,
    KEY idx_team_task_comment (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
