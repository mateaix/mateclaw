# PostgreSQL 部署

MateClaw 把 PostgreSQL 作为**一等公民**支持：独立迁移树、JSONB 列、真实数据库集成测试。本页讲怎么用 PostgreSQL 跑生产，以及它和 MySQL 的差异。

> 支持版本：**PostgreSQL 14+**。KingbaseES（人大金仓）与 PG 同源，用 `kingbase` profile，方言一致但 JSON 列保持 `TEXT`。

## 快速开始（Docker）

```bash
cp .env.example .env
# 编辑 .env：至少设置 PGSQL_DB_PASSWORD（强密码）
# 注意：即便不用 MySQL，docker compose 解析阶段仍要求 DB_PASSWORD / DB_ROOT_PASSWORD
#       有值（任意占位即可，MySQL 容器不会启动）

docker compose -f docker-compose.yml -f docker-compose.pg.yml up -d
```

叠加文件 `docker-compose.pg.yml` 做三件事：把 `mateclaw-server` 切到 `postgres` profile、指向 `postgres` 容器、并把 MySQL 容器挡在 `mysql` profile 后面不启动。

## 手动部署（非 Docker）

激活 `postgres` profile，配好连接参数：

```bash
java -jar mateclaw-server.jar \
  --spring.profiles.active=postgres \
  --DB_HOST=your-pg-host --DB_PORT=5432 \
  --DB_NAME=mateclaw --DB_USERNAME=postgres --DB_PASSWORD=...
```

连接串关键参数（见 `application-postgres.yml`）：

```
jdbc:postgresql://HOST:PORT/DB?currentSchema=mateclaw&stringtype=unspecified
```

- **`currentSchema=mateclaw`** —— MateClaw 所有表建在 `mateclaw` schema（不是 `public`）。Flyway 启动时自动 `CREATE SCHEMA IF NOT EXISTS mateclaw`，HikariCP 每个连接再 `SET search_path TO mateclaw` 兜底。
- **`stringtype=unspecified`** —— **必填**。见下方 JSONB 说明。

## JSONB 设计

PostgreSQL 迁移树（`db/migration/postgresql`）是从 KingbaseES 树 fork 出来的，把约 40 个高频 JSON 列从 `TEXT` 升级为 **`JSONB`**（`config_json` / `headers_json` / `settings_json` / `delivery_config` / …）。收益：**写入时数据库强制校验 JSON 合法性**，非法 JSON 直接被拒。

### 为什么必须 `stringtype=unspecified`

MyBatis / JDBC 把 JSON 列的值按 `java.lang.String` 用 `setString` 绑定，PG 默认把它当 `varchar`。往 `jsonb` 列写 `varchar` 会报：

```
ERROR: column "xxx" is of type jsonb but expression is of type character varying
```

`stringtype=unspecified` 让驱动把 String 参数标为 `unknown`，PG 自动 cast 成 jsonb 并校验。这覆盖了两条写入路径：`JacksonTypeHandler`（`CronJobEntity.deliveryConfig`）和普通 String JSON 列。

### 哪些列没转 JSONB

刻意保持 `TEXT`：

| 列 | 表 | 原因 |
|---|---|---|
| `params_schema` | `mate_tool` | 任意 JSON Schema 文本 |
| `output_schema` | `mate_wiki_transformation` | 同上 |
| `metadata` | `mate_message` | 高频截断的半结构化 blob |

### JSON 查询与索引

目前代码不按 JSON 字段查询，所以**没有建 GIN 索引**。未来需要按 JSON 键过滤时，再加：

```sql
CREATE INDEX idx_xxx_gin ON mateclaw.your_table USING GIN (your_col jsonb_path_ops);
```

JSONB 列可直接用 `->`/`->>`/`@>` 查询，例如 `SELECT delivery_config ->> 'targetId' FROM mate_cron_job`。

## 与 MySQL 的差异

| 方面 | MySQL | PostgreSQL |
|---|---|---|
| 迁移树 | `db/migration/mysql` | `db/migration/postgresql` |
| upsert | `ON DUPLICATE KEY UPDATE` | `ON CONFLICT ... DO UPDATE` |
| 主键 | 雪花（应用层生成，两者相同） | 雪花（相同） |
| 逻辑删除标记 | `INT` | `SMALLINT`（对应 Java `Integer`） |
| JSON 存储 | `JSON` / `TEXT` | `JSONB` |
| schema | 库即 schema | 独立 `mateclaw` schema |

## 备份

```bash
# 备份（只导 mateclaw schema）
pg_dump -h HOST -U postgres -n mateclaw -Fc mateclaw > mateclaw.dump

# 恢复
pg_restore -h HOST -U postgres -d mateclaw --clean mateclaw.dump
```

## 从「寄生 kingbase 树」升级

早期版本的 PostgreSQL 复用 `db/migration/kingbase` 迁移树。现在 PG 有独立的 `db/migration/postgresql` 树。由于两棵树 fork 时字节一致、Flyway 按 version+checksum（而非路径）判定，**已有 PG 部署切换 location 是透明的**——不会重跑、不会冲突。JSONB 升级只影响**新建库**的列类型；已用 TEXT 跑起来的老库不会被自动 ALTER（如需享受 JSONB，请在新库部署或手动迁移列类型）。

## 验证

PG 迁移树由 Testcontainers 集成测试守护（需本机有 Docker）：

```bash
mvn -Dtest='PostgresMigrationSmokeTest,CronJobDeliveryConfigPgTest' test
```

覆盖：全部迁移在真实 PG 上跑通、JSONB 列物理类型正确、JacksonTypeHandler 读写往返、非法 JSON 被拒。无 Docker 的机器上这些测试自动跳过（不失败）。
