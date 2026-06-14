-- Convert the three default seed agents from emoji to pixelarticons icons.
-- The card UI now treats pi:<name> as an inline pixel-art SVG, so the
-- defaults need to match. Guarded with the original emoji so a user who
-- already customised the icon keeps their choice; non-seed (user-created)
-- agents are intentionally untouched.
UPDATE mate_agent SET icon = 'pi:robot-face-happy' WHERE id = 1000000001 AND icon = '🤖';
UPDATE mate_agent SET icon = 'pi:clipboard-note'   WHERE id = 1000000002 AND icon = '📋';
UPDATE mate_agent SET icon = 'pi:cpu'              WHERE id = 1000000003 AND icon = '🔄';
