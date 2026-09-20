-- Durable liveness for long cron runs (MySQL dialect).
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run'
             AND COLUMN_NAME = 'heartbeat_at');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_cron_job_run ADD COLUMN heartbeat_at TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run'
             AND INDEX_NAME = 'idx_cron_run_status_heartbeat');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_cron_run_status_heartbeat ON mate_cron_job_run(status, heartbeat_at)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
