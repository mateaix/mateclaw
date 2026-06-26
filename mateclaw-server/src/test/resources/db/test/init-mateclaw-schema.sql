-- Pre-create the mateclaw schema before Flyway runs. PostgreSQL does not
-- auto-create a non-public schema, and the test datasource connects with
-- currentSchema=mateclaw (matching production application-postgres.yml).
CREATE SCHEMA IF NOT EXISTS mateclaw;
