-- V126: Two binding-mode flags on mate_agent (KingbaseES).
--
-- skills_disabled / tools_disabled flip the "zero binding rows" semantic from
-- "inherit every globally-enabled capability" to "this agent has explicitly
-- opted out". Without these columns, an operator who wanted an agent with no
-- skills had to bind a dummy skill — otherwise the runtime fell back to the
-- global default and every skill's catalog entry got injected into the system
-- prompt (issue #184).
--
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS skills_disabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS tools_disabled SMALLINT NOT NULL DEFAULT 0;
