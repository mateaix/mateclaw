-- V67: Skill manifest_json column (RFC-090 Phase 2)
-- Stores the full parsed SKILL.md frontmatter as JSON. This becomes the
-- source of truth (RFC-090 §14.6); existing columns (skill_type, icon,
-- version, author) are kept as index projections, written by
-- SkillPackageResolver.projectManifestToColumns after each resolve.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'manifest_json'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN manifest_json TEXT;
    END IF;
END $$;
