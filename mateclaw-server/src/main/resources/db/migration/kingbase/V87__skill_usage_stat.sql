CREATE TABLE IF NOT EXISTS mate_skill_usage_stat (
    id                 BIGINT  PRIMARY KEY,
    skill_name          VARCHAR(128) NOT NULL,
    skill_id            BIGINT,
    agent_id            BIGINT NOT NULL DEFAULT 0,
    conversation_id     VARCHAR(128) NOT NULL DEFAULT '',
    load_count          BIGINT NOT NULL DEFAULT 0,
    last_loaded_at      TIMESTAMP(3),
    last_file_path      VARCHAR(512),
    last_token_estimate INT,
    create_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted             SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_usage_scope ON mate_skill_usage_stat (skill_name, agent_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_skill_usage_agent_recent ON mate_skill_usage_stat (agent_id, last_loaded_at);
CREATE INDEX IF NOT EXISTS idx_skill_usage_name_recent ON mate_skill_usage_stat (skill_name, last_loaded_at);
