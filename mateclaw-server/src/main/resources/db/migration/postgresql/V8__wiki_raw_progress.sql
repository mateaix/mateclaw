-- V8: wiki raw material two-phase digest progress fields, for UI progress bar
-- RFC-012 M2 v2 UI follow-up: expose per-raw progress (current phase + pages done / total planned)
-- so the frontend can render a determinate progress bar instead of an opaque "处理中" badge.
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'progress_phase'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_phase VARCHAR(32) DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'progress_total'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_total INT DEFAULT 0;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'progress_done'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_done INT DEFAULT 0;
    END IF;
END $$;
