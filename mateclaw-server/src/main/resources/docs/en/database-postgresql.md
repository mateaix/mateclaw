# PostgreSQL deployment

MateClaw supports PostgreSQL as a **first-class** target: its own migration
tree, JSONB columns, and real-database integration tests. This page covers
running production on PostgreSQL and how it differs from MySQL.

> Supported: **PostgreSQL 14+**. KingbaseES is PostgreSQL-family — use the
> `kingbase` profile; same dialect but JSON columns stay `TEXT`.

## Quick start (Docker)

```bash
cp .env.example .env
# Edit .env: set at least PGSQL_DB_PASSWORD (a strong password).
# Note: docker compose still interpolates the base mysql service's required
#       vars at config time, so DB_PASSWORD / DB_ROOT_PASSWORD must have any
#       non-empty placeholder value (the mysql container never starts).

docker compose -f docker-compose.yml -f docker-compose.pg.yml up -d
```

The override `docker-compose.pg.yml` does three things: switches
`mateclaw-server` to the `postgres` profile, points it at the `postgres`
container, and gates the MySQL container behind a `mysql` profile so it never
starts.

## Manual deployment (non-Docker)

Activate the `postgres` profile and supply the connection parameters:

```bash
java -jar mateclaw-server.jar \
  --spring.profiles.active=postgres \
  --DB_HOST=your-pg-host --DB_PORT=5432 \
  --DB_NAME=mateclaw --DB_USERNAME=postgres --DB_PASSWORD=...
```

Key connection-string params (see `application-postgres.yml`):

```
jdbc:postgresql://HOST:PORT/DB?currentSchema=mateclaw&stringtype=unspecified
```

- **`currentSchema=mateclaw`** — all tables live in the `mateclaw` schema (not
  `public`). Flyway runs `CREATE SCHEMA IF NOT EXISTS mateclaw` on startup, and
  HikariCP runs `SET search_path TO mateclaw` per connection as a backstop.
- **`stringtype=unspecified`** — **required**. See the JSONB notes below.

## JSONB design

The PostgreSQL migration tree (`db/migration/postgresql`) is forked from the
KingbaseES tree and upgrades ~40 high-frequency JSON columns from `TEXT` to
**`JSONB`** (`config_json` / `headers_json` / `settings_json` /
`delivery_config` / …). Payoff: **the database validates JSON well-formedness at
write time** — malformed JSON is rejected.

### Why `stringtype=unspecified` is required

MyBatis / JDBC bind JSON column values as `java.lang.String` via `setString`,
which PostgreSQL types as `varchar`. Writing a `varchar` into a `jsonb` column
fails:

```
ERROR: column "xxx" is of type jsonb but expression is of type character varying
```

`stringtype=unspecified` makes the driver send String params as `unknown`, so
PostgreSQL coerces them into jsonb (and validates). This covers both write
paths: `JacksonTypeHandler` (`CronJobEntity.deliveryConfig`) and plain String
JSON columns.

### Columns deliberately kept TEXT

| Column | Table | Reason |
|---|---|---|
| `params_schema` | `mate_tool` | Arbitrary JSON-Schema text |
| `output_schema` | `mate_wiki_transformation` | Same |
| `metadata` | `mate_message` | Frequently-truncated half-structured blob |

### JSON queries and indexes

No code queries JSON fields today, so **no GIN index is created**. When you need
to filter by a JSON key, add one:

```sql
CREATE INDEX idx_xxx_gin ON mateclaw.your_table USING GIN (your_col jsonb_path_ops);
```

JSONB columns are queryable with `->`/`->>`/`@>`, e.g.
`SELECT delivery_config ->> 'targetId' FROM mate_cron_job`.

## Differences from MySQL

| Aspect | MySQL | PostgreSQL |
|---|---|---|
| Migration tree | `db/migration/mysql` | `db/migration/postgresql` |
| upsert | `ON DUPLICATE KEY UPDATE` | `ON CONFLICT ... DO UPDATE` |
| Primary key | Snowflake (app-side; same) | Snowflake (same) |
| Logical-delete flag | `INT` | `SMALLINT` (maps to Java `Integer`) |
| JSON storage | `JSON` / `TEXT` | `JSONB` |
| schema | database == schema | dedicated `mateclaw` schema |

## Backups

```bash
# Back up the mateclaw schema only
pg_dump -h HOST -U postgres -n mateclaw -Fc mateclaw > mateclaw.dump

# Restore
pg_restore -h HOST -U postgres -d mateclaw --clean mateclaw.dump
```

## Upgrading from the "parasitic kingbase tree"

Earlier versions ran PostgreSQL on the `db/migration/kingbase` tree. PostgreSQL
now has its own `db/migration/postgresql` tree. Because the two trees are
byte-identical at fork time and Flyway keys on version+checksum (not path),
**switching locations is transparent for existing PG deployments** — nothing
re-runs, nothing conflicts. The JSONB upgrade only affects column types on a
**freshly created** database; an existing TEXT-based database is not auto-ALTERed
(deploy a new database, or migrate the column types manually, to gain JSONB).

## Verification

The PG migration tree is guarded by Testcontainers integration tests (needs
Docker locally):

```bash
mvn -Dtest='PostgresMigrationSmokeTest,CronJobDeliveryConfigPgTest' test
```

Coverage: every migration applies on a real PG server, JSONB columns have the
right physical type, the JacksonTypeHandler round-trips, and malformed JSON is
rejected. On machines without Docker these tests are skipped (not failed).
