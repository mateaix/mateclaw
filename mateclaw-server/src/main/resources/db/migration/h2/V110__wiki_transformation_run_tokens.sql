-- Record per-run token usage so operators can see which templates burn the
-- most tokens and which models produce the most expensive output. Spring AI
-- surfaces the values via ChatResponseMetadata.getUsage(); the executor
-- snapshots them into the run row after the LLM call.

ALTER TABLE mate_wiki_transformation_run ADD COLUMN IF NOT EXISTS input_tokens BIGINT NULL;
ALTER TABLE mate_wiki_transformation_run ADD COLUMN IF NOT EXISTS output_tokens BIGINT NULL;
ALTER TABLE mate_wiki_transformation_run ADD COLUMN IF NOT EXISTS total_tokens BIGINT NULL;
