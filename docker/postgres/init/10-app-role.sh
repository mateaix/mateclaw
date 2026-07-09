#!/bin/sh
# ============================================================================
# Create a least-privilege application role for the MateClaw server.
#
# Runs once, during first container init (empty data dir), as the bootstrap
# superuser (POSTGRES_USER) against POSTGRES_DB. The app role:
#   - can log in and CONNECT to the database,
#   - owns the `mateclaw` schema (so Flyway can create/alter tables in it),
#   - is NOT a superuser and cannot touch other databases/roles.
#
# The server connects as APP_DB_USERNAME / APP_DB_PASSWORD.
# ============================================================================
set -e

# Pass credentials as psql variables (-v) rather than interpolating them into
# the SQL text. The quoted heredoc (<<'EOSQL') keeps the body literal, and psql
# does the quoting: :'var' -> safe string literal, :"var" -> safe identifier.
# CREATE ROLE is generated via format(%I, %L) + \gexec so a password containing
# a quote (or an exotic role name) can't break or inject into the statement.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v app_user="$APP_DB_USERNAME" \
    -v app_pw="$APP_DB_PASSWORD" \
    -v db="$POSTGRES_DB" <<'EOSQL'
    SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_pw')
    WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')
    \gexec

    -- CONNECT to use the database; CREATE so the role can create schemas in it.
    -- CREATE is required because Flyway's init-sql runs CREATE SCHEMA IF NOT
    -- EXISTS, and PostgreSQL checks the database-level CREATE privilege *before*
    -- the IF NOT EXISTS short-circuit — so even a pre-existing schema is denied
    -- without it. Still scoped to this one database; not a cluster superuser.
    GRANT CONNECT, CREATE ON DATABASE :"db" TO :"app_user";

    -- The app owns its schema so Flyway DDL works, without cluster superuser rights.
    CREATE SCHEMA IF NOT EXISTS mateclaw AUTHORIZATION :"app_user";

    -- Default to the app schema on every connection from this role.
    ALTER ROLE :"app_user" SET search_path TO mateclaw, public;
EOSQL

echo "[init] application role '${APP_DB_USERNAME}' and schema 'mateclaw' ready"
