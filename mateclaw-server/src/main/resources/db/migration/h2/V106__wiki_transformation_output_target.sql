-- Two-part follow-up to V105 so a transformation's output can flow back
-- into the KB as a first-class artifact:
--
-- 1. mate_wiki_transformation.output_target — declarative target for the
--    template's output. `none` = legacy behaviour (output stays in the run
--    history only). `page` = after a successful run, persist the output as
--    a synthesis wiki page derived from the source raw material. Runs an
--    upsert against a deterministic slug so re-running is idempotent.
--
-- 2. mate_wiki_transformation_run.output_page_id — when a run was saved as
--    a page (either via apply_default=page or the manual save-as-page
--    endpoint), this points at mate_wiki_page.id so the UI can render a
--    "saved as: <slug>" link without a join through sourceRawIds.

ALTER TABLE mate_wiki_transformation
    ADD COLUMN IF NOT EXISTS output_target VARCHAR(16) NOT NULL DEFAULT 'none';

ALTER TABLE mate_wiki_transformation_run
    ADD COLUMN IF NOT EXISTS output_page_id BIGINT NULL;
