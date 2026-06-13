-- Dream v2: structured dream report (rfc-035 §4.4)
CREATE TABLE IF NOT EXISTS mate_dream_report (
    id              BIGINT PRIMARY KEY,
    agent_id        BIGINT NOT NULL,
    mode            VARCHAR(32) NOT NULL,
    topic           VARCHAR(256),
    trigger_source  VARCHAR(32) NOT NULL,
    triggered_by    VARCHAR(64),
    started_at      TIMESTAMP NOT NULL,
    finished_at     TIMESTAMP NOT NULL,
    candidate_count INT NOT NULL,
    promoted_count  INT NOT NULL,
    rejected_count  INT NOT NULL,
    memory_diff     TEXT,
    llm_reason      TEXT,
    status          VARCHAR(16) NOT NULL,
    error_message   TEXT,
    create_time     TIMESTAMP NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_dream_agent_time ON mate_dream_report(agent_id, started_at DESC);
CREATE INDEX idx_dream_agent_mode ON mate_dream_report(agent_id, mode, started_at DESC);
