-- Per-conversation model selection. Each conversation pins the LLM it uses, so
-- switching the model in one chat no longer changes every other conversation.
-- NULL means "inherit": fall back to the agent's model override, then to the
-- global default model.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS model_provider VARCHAR(64);
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS model_name VARCHAR(128);
