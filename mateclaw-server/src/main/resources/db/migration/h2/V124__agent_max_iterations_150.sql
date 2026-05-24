-- V124: bump default agents' max_iterations 100 → 150 to support longer
-- multi-step research workflows (10+ items × per-step browser verification).
--
-- Calibrated from the round-4 LLM-review smoke test: a "research 10 LLMs,
-- write a section per model, summarise, generate PPTX" task hit the previous
-- 100-iter ceiling with only 4/10 models completed because each model takes
-- ~25 iterations of browser_use + read_file + edit_file + progress_update.
-- 150 gives ~50 % headroom while still bounding a runaway agent.
--
-- Idempotent: only updates rows still holding the previous default (100) so
-- user-customised agents are not touched. BaseAgent's hard ceiling is also
-- raised to 150; AgentGraphBuilder clamps DB overrides against it.

UPDATE mate_agent SET max_iterations = 150
WHERE id IN (1000000001, 1000000002, 1000000003) AND max_iterations = 100;
