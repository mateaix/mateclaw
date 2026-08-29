-- Add a runtime provider selector without changing the legacy agent_type.
ALTER TABLE mate_agent
    ADD COLUMN IF NOT EXISTS runtime_type VARCHAR(32) NOT NULL DEFAULT 'native';

ALTER TABLE mate_agent
    ADD COLUMN IF NOT EXISTS runtime_config CLOB DEFAULT NULL;

UPDATE mate_agent
SET runtime_type = 'native'
WHERE runtime_type IS NULL OR TRIM(runtime_type) = '';
