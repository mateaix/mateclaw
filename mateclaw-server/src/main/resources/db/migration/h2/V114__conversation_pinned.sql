-- Per-conversation pin flag. A pinned conversation sorts ahead of unpinned
-- ones in the sidebar list regardless of last-active time, so users can keep
-- important conversations reachable in one glance. 0 = normal, 1 = pinned.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS pinned INT DEFAULT 0;
