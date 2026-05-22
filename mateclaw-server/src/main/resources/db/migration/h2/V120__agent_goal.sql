-- Persistent goal: cross-turn objective lock-in with self-evaluation loop.
--
-- A goal binds to a single conversation and stays active across many user
-- turns. After each agent reply the GoalEvaluationNode rates progress and
-- can optionally inject a follow-up prompt. The status state machine is:
--     active -> { paused | completed | abandoned | exhausted }
-- with paused -> active as the only reverse edge.
--
-- DB-level uniqueness for "at most one active goal per conversation":
-- We use a virtual generated column that yields conversation_id only when
-- the row is in active state (and not soft-deleted) — NULL otherwise — and
-- a plain UNIQUE constraint over it. NULLs do not participate in
-- uniqueness checks (SQL standard), so terminal-state rows coexist with
-- the next active goal on the same conversation. H2 2.x supports the
-- exact same syntax as MySQL for virtual generated columns; we use it
-- here rather than a predicate index (which H2 lacks).

CREATE TABLE IF NOT EXISTS mate_agent_goal (
    id                          BIGINT       NOT NULL,
    conversation_id             VARCHAR(64)  NOT NULL,
    agent_id                    BIGINT       NOT NULL,
    workspace_id                BIGINT       NOT NULL,
    created_by                  VARCHAR(64)  NOT NULL,

    title                       VARCHAR(255) NOT NULL,
    description                 CLOB         NOT NULL,
    exit_criteria               CLOB         NULL,
    success_check_prompt        CLOB         NULL,

    -- DB values are always lowercase (active|paused|completed|abandoned|
    -- exhausted) — enforced by the GoalStatus enum's @EnumValue
    -- annotation. The generated column below depends on this convention.
    status                      VARCHAR(16)  NOT NULL DEFAULT 'active',

    turn_budget                 INT          NOT NULL DEFAULT 20,
    turns_used                  INT          NOT NULL DEFAULT 0,
    llm_call_budget             INT          NOT NULL DEFAULT 200,
    agent_llm_calls_used        INT          NOT NULL DEFAULT 0,
    eval_llm_calls_used         INT          NOT NULL DEFAULT 0,

    progress_summary            CLOB         NULL,
    completion_score            DOUBLE       NULL,
    last_evaluation_at          TIMESTAMP    NULL,

    auto_followup_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    followup_cooldown_seconds   INT          NOT NULL DEFAULT 0,
    last_followup_at            TIMESTAMP    NULL,

    version                     INT          NOT NULL DEFAULT 0,
    deleted                     INT          NOT NULL DEFAULT 0,
    create_time                 TIMESTAMP    NOT NULL,
    update_time                 TIMESTAMP    NOT NULL,

    -- Virtual generated column: yields the conversation id only for the
    -- "live active" subset, NULL otherwise. A plain UNIQUE constraint
    -- over it gives "at most one active row per conversation" while
    -- letting any number of completed/abandoned/exhausted/deleted rows
    -- coexist (NULLs are non-comparable under UNIQUE).
    active_conv_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status = 'active' AND deleted = 0
             THEN conversation_id ELSE NULL END
    ),

    PRIMARY KEY (id),
    CONSTRAINT uk_agent_goal_active_conv UNIQUE (active_conv_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_goal_conv
    ON mate_agent_goal(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_goal_status
    ON mate_agent_goal(status, last_evaluation_at);
CREATE INDEX IF NOT EXISTS idx_agent_goal_owner
    ON mate_agent_goal(created_by, status);

-- Append-only event log: timeline view in the drawer reads from here.
-- event_type values are documented in vip.mate.goal.model.GoalEventType.
CREATE TABLE IF NOT EXISTS mate_agent_goal_event (
    id           BIGINT       NOT NULL,
    goal_id      BIGINT       NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    message_id   BIGINT       NULL,
    detail_json  CLOB         NULL,
    create_time  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_agent_goal_event_goal
    ON mate_agent_goal_event(goal_id, id);
