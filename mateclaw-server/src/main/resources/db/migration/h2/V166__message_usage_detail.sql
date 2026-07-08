-- V166: Per-message token usage detail for the chat consumption breakdown panel.
-- Adds prompt-cache hit/write and reasoning token counters to mate_message so the
-- UI can show input cache hit/miss/write and thinking-vs-reply output splits.
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS cache_read_tokens INT DEFAULT 0;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS cache_write_tokens INT DEFAULT 0;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS reasoning_tokens INT DEFAULT 0;
