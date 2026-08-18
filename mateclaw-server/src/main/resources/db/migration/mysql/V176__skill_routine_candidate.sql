-- V176: Recurring-request candidates for routine mining.
-- A single conversation cannot show that a request is habitual, so the
-- reflection reviewer (which only ever sees one window) correctly treats
-- repeat work as a one-off narrative. This table accumulates the cross-session
-- evidence that reflection structurally cannot see: how many separate
-- conversations opened with substantially the same request, over how many
-- distinct days. Once a cluster clears the recurrence gate it is promoted into
-- a class-level skill. Indexes are declared inline because MySQL does not
-- support CREATE INDEX IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS mate_skill_routine_candidate (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    agent_id              BIGINT       NOT NULL,
    workspace_id          BIGINT,
    -- Normalized representative text of the cluster; the human-readable
    -- identity of the routine ("summarize today's on-call alerts").
    signature             VARCHAR(512) NOT NULL,
    -- Stable hash of the signature, used as the upsert key. A hash rather
    -- than the signature itself so the unique index stays inside index key
    -- length limits on every dialect.
    signature_hash        VARCHAR(64)  NOT NULL,
    -- Verbatim opener of the most recent member conversation, kept for the
    -- synthesis prompt so it works from real phrasing, not the normalized form.
    representative_text   VARCHAR(2048),
    -- JSON array of member conversation ids, capped by the miner.
    sample_conversations  MEDIUMTEXT,
    occurrence_count      INT          NOT NULL DEFAULT 0,
    distinct_day_count    INT          NOT NULL DEFAULT 0,
    first_seen_at         DATETIME,
    last_seen_at          DATETIME,
    -- observing = accumulating evidence; promoted = a skill was synthesized;
    -- dismissed = operator rejected, never re-promote.
    status                VARCHAR(16)  NOT NULL DEFAULT 'observing',
    promoted_skill_name   VARCHAR(128),
    promoted_at           DATETIME,
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_routine_agent_signature (agent_id, signature_hash, deleted),
    KEY idx_routine_status (status, occurrence_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recurring user-request clusters awaiting skill promotion';
