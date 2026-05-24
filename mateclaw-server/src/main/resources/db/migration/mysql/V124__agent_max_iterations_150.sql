-- V124: bump default agents' max_iterations 100 → 150 (see H2 copy for full
-- background). Same idempotent UPDATE — H2 and MySQL accept identical syntax
-- for this UPDATE so no dialect-specific guard is needed.

UPDATE mate_agent SET max_iterations = 150
WHERE id IN (1000000001, 1000000002, 1000000003) AND max_iterations = 100;
