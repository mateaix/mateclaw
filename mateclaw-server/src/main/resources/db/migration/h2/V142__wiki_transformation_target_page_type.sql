-- Optional target pageType for a transformation whose output_target='page'.
-- When set, a run persisted as a wiki page is classified with this pageType
-- (normalised against the KB's pageType profile at save time). When NULL the
-- save falls back to the profile's fallbackType, so transformation output is
-- always a first-class member of the KB's classification rather than a
-- hard-coded "synthesis" type that sits outside every profile.

ALTER TABLE mate_wiki_transformation
    ADD COLUMN IF NOT EXISTS target_page_type VARCHAR(64) DEFAULT NULL;
