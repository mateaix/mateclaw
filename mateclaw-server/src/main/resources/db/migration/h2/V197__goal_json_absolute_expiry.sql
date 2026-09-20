-- Absolute acceptance times must not depend on a JDBC/JVM session timezone.
-- Legacy wall-clock timestamps have no recoverable zone: keep their bodies and
-- generations, but expire their eligibility rather than guessing an offset.
ALTER TABLE mate_goal_json_artifact ADD COLUMN created_epoch_second BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mate_goal_json_artifact ADD COLUMN expires_epoch_second BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mate_goal_json_binding ADD COLUMN expires_epoch_second BIGINT NOT NULL DEFAULT 0;
