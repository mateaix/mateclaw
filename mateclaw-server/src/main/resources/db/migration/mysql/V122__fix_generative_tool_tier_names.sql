-- V122__fix_generative_tool_tier_names.sql (MySQL dialect)
--
-- Corrective migration. The first cut of V121 seeded the generative / browser
-- tools as extension using their @Tool function names (image_generate, ...),
-- but mate_tool.name stores the Java class name (ImageGenerateTool, ...), so the
-- UPDATE matched no rows on databases that ran that early version. Re-apply the
-- seed by class name. Idempotent: only promotes core → extension and leaves any
-- admin-set value untouched.

UPDATE mate_tool
SET disclosure_tier = 'extension'
WHERE name IN ('ImageGenerateTool', 'MusicGenerateTool', 'VideoGenerateTool', 'Model3dGenerateTool', 'BrowserUseTool')
  AND (disclosure_tier IS NULL OR disclosure_tier = 'core');
