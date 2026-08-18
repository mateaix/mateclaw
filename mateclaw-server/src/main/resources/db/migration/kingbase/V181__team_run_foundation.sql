CREATE TABLE IF NOT EXISTS mate_team_run (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    team_id               BIGINT        NOT NULL,
    workspace_id          BIGINT        NOT NULL,
    lead_agent_id         BIGINT        NOT NULL,
    lead_conversation_id  VARCHAR(64)   NOT NULL,
    origin_message_id     BIGINT        NULL,
    title                 VARCHAR(255)  NOT NULL,
    objective             TEXT          NOT NULL,
    status                VARCHAR(32)   NOT NULL DEFAULT 'planning',
    final_summary         TEXT,
    stop_reason           VARCHAR(1000),
    metadata              TEXT,
    started_at            TIMESTAMP     NULL,
    completed_at          TIMESTAMP     NULL,
    create_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT           DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_team_run_team_history
    ON mate_team_run (team_id, create_time);
CREATE INDEX IF NOT EXISTS idx_team_run_conversation_history
    ON mate_team_run (lead_conversation_id, create_time);
CREATE INDEX IF NOT EXISTS idx_team_run_status
    ON mate_team_run (status, update_time);
CREATE UNIQUE INDEX IF NOT EXISTS uk_team_run_origin_message
    ON mate_team_run (workspace_id, lead_conversation_id, origin_message_id);

ALTER TABLE mate_team_task
    ADD COLUMN IF NOT EXISTS run_id BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_team_task_run_number
    ON mate_team_task (run_id, task_number);
CREATE INDEX IF NOT EXISTS idx_team_task_run_status
    ON mate_team_task (run_id, status);
