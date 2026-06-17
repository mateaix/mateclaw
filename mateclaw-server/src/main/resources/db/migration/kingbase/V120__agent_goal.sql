-- Persistent goal — see h2/V120__agent_goal.sql for full design notes.
--
-- Kingbase/PostgreSQL differences vs H2:
--   1. CLOB -> TEXT
--   2. TIMESTAMP -> TIMESTAMP(3) for millisecond precision matching V117
--   3. BOOLEAN -> SMALLINT
--   4. H2 uses a PREDICATE unique index for "one active goal per
--      conversation"; PostgreSQL does not support filtered unique indexes,
--      so we emulate it with a STORED generated column that is NULL for
--      non-active rows + a plain unique index. NULLs are excluded from
--      uniqueness enforcement by PostgreSQL's default index semantics.

CREATE TABLE IF NOT EXISTS mate_agent_goal (
    id                          BIGINT       NOT NULL,
    conversation_id             VARCHAR(64)  NOT NULL,
    agent_id                    BIGINT       NOT NULL,
    workspace_id                BIGINT       NOT NULL,
    created_by                  VARCHAR(64)  NOT NULL,

    title                       VARCHAR(255) NOT NULL,
    description                 TEXT     NOT NULL,
    exit_criteria               TEXT     NULL,
    success_check_prompt        TEXT     NULL,

    -- DB values are always lowercase (active|paused|completed|abandoned|
    -- exhausted) — enforced by the GoalStatus enum's @EnumValue
    -- annotation. The active_conv_key generated column below depends on
    -- this convention; any uppercase write would defeat uniqueness.
    status                      VARCHAR(16)  NOT NULL DEFAULT 'active',

    turn_budget                 INT          NOT NULL DEFAULT 20,
    turns_used                  INT          NOT NULL DEFAULT 0,
    llm_call_budget             INT          NOT NULL DEFAULT 200,
    agent_llm_calls_used        INT          NOT NULL DEFAULT 0,
    eval_llm_calls_used         INT          NOT NULL DEFAULT 0,

    progress_summary            TEXT     NULL,
    completion_score            DOUBLE PRECISION       NULL,
    last_evaluation_at          TIMESTAMP(3)  NULL,

    auto_followup_enabled       BOOLEAN   NOT NULL DEFAULT FALSE,
    followup_cooldown_seconds   INT          NOT NULL DEFAULT 0,
    last_followup_at            TIMESTAMP(3)  NULL,

    -- Virtual generated column: NULL for non-active or deleted rows so
    -- they fall out of the unique-index check. PostgreSQL ignores NULL keys
    -- for uniqueness, giving us "at most one active row per conversation".
    active_conv_key             VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'active' AND deleted = 0
                 THEN conversation_id ELSE NULL END
        ) STORED,

    version                     INT          NOT NULL DEFAULT 0,
    deleted                     SMALLINT   NOT NULL DEFAULT 0,
    create_time                 TIMESTAMP(3)  NOT NULL,
    update_time                 TIMESTAMP(3)  NOT NULL,

    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_goal_active_conv ON mate_agent_goal (active_conv_key);
CREATE INDEX IF NOT EXISTS idx_agent_goal_conv ON mate_agent_goal (conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_goal_status ON mate_agent_goal (status, last_evaluation_at);
CREATE INDEX IF NOT EXISTS idx_agent_goal_owner ON mate_agent_goal (created_by, status);

CREATE TABLE IF NOT EXISTS mate_agent_goal_event (
    id           BIGINT       NOT NULL,
    goal_id      BIGINT       NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    message_id   BIGINT       NULL,
    detail_json  TEXT     NULL,
    create_time  TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_agent_goal_event_goal ON mate_agent_goal_event (goal_id, id);
