-- V11: Auto Skill Synthesis (RFC-023)
-- Agent 自治创建 skill 后记录来源对话和安全扫描状态
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'source_conversation_id'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN source_conversation_id VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;

-- security_scan_status: NULL(旧数据/手动创建) / PASSED / FAILED
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'security_scan_status'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN security_scan_status VARCHAR(16) DEFAULT NULL;
    END IF;
END $$;
