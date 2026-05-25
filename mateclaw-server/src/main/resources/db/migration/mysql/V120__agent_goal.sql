-- Persistent goal — see h2/V120__agent_goal.sql for full design notes.
--
-- MySQL-specific differences vs H2:
--   1. CLOB -> LONGTEXT
--   2. TIMESTAMP -> DATETIME(3) for millisecond precision matching V117
--   3. BOOLEAN -> TINYINT(1)
--   4. H2 uses a PREDICATE unique index for "one active goal per
--      conversation"; MySQL InnoDB does not support filtered indexes,
--      so we emulate it with a virtual generated column that is NULL for
--      non-active rows + a plain unique index. NULLs are excluded from
--      uniqueness enforcement by MySQL's default index semantics.

CREATE TABLE IF NOT EXISTS mate_agent_goal (
    id                          BIGINT       NOT NULL,
    conversation_id             VARCHAR(64)  NOT NULL,
    agent_id                    BIGINT       NOT NULL,
    workspace_id                BIGINT       NOT NULL,
    created_by                  VARCHAR(64)  NOT NULL,

    title                       VARCHAR(255) NOT NULL,
    description                 LONGTEXT     NOT NULL,
    exit_criteria               LONGTEXT     NULL,
    success_check_prompt        LONGTEXT     NULL,

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

    progress_summary            LONGTEXT     NULL,
    completion_score            DOUBLE       NULL,
    last_evaluation_at          DATETIME(3)  NULL,

    auto_followup_enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    followup_cooldown_seconds   INT          NOT NULL DEFAULT 0,
    last_followup_at            DATETIME(3)  NULL,

    -- Virtual generated column: NULL for non-active or deleted rows so
    -- they fall out of the unique-index check. InnoDB ignores NULL keys
    -- for uniqueness, giving us "at most one active row per conversation".
    active_conv_key             VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'active' AND deleted = 0
                 THEN conversation_id ELSE NULL END
        ) VIRTUAL,

    version                     INT          NOT NULL DEFAULT 0,
    deleted                     TINYINT(1)   NOT NULL DEFAULT 0,
    create_time                 DATETIME(3)  NOT NULL,
    update_time                 DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_goal_active_conv (active_conv_key),
    KEY idx_agent_goal_conv     (conversation_id, status),
    KEY idx_agent_goal_status   (status, last_evaluation_at),
    KEY idx_agent_goal_owner    (created_by, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_agent_goal_event (
    id           BIGINT       NOT NULL,
    goal_id      BIGINT       NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    message_id   BIGINT       NULL,
    detail_json  LONGTEXT     NULL,
    create_time  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_goal_event_goal (goal_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
