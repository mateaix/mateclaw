-- Independent revision of the goal evaluation definition; usage/version updates do not advance it.
ALTER TABLE mate_agent_goal ADD COLUMN evaluation_revision BIGINT NOT NULL DEFAULT 0;
