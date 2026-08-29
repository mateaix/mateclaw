-- Add a runtime provider selector without changing the legacy agent_type.
ALTER TABLE mate_agent
    ADD COLUMN IF NOT EXISTS runtime_type VARCHAR(32) DEFAULT 'native';

ALTER TABLE mate_agent
    ADD COLUMN IF NOT EXISTS runtime_config TEXT DEFAULT NULL;

UPDATE mate_agent
SET runtime_type = 'native'
WHERE runtime_type IS NULL OR TRIM(runtime_type) = '';

ALTER TABLE mate_agent
    ALTER COLUMN runtime_type SET DEFAULT 'native';
