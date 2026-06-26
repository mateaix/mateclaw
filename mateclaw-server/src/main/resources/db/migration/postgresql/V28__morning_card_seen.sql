-- Dream v2 Phase 2b: Morning Card seen state per (user, agent)
-- Ref: rfc-034 F5 — DO NOT add to mate_user; use separate table
CREATE TABLE IF NOT EXISTS mate_morning_card_seen (
    id              BIGINT PRIMARY KEY ,
    user_id         BIGINT NOT NULL,
    agent_id        BIGINT NOT NULL,
    last_seen_at    TIMESTAMP NOT NULL,
    last_report_id  BIGINT,
    create_time     TIMESTAMP NOT NULL,
    update_time     TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_agent ON mate_morning_card_seen (user_id, agent_id);
