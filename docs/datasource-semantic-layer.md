# 数据源扩展：语义层架构

## Issue 概述

目前 MateClaw 的数据源仅支持数据库（MySQL/PostgreSQL/ClickHouse）一种模式。需要扩展为统一的语义数据源平台，支持多种后端存储，同时为 LLM 提供语义可读的 schema 信息。

## 背景

### 现状
- `datasource` 模块仅支持 JDBC 数据库连接
- 通过 `SqlQueryTool` 和 `DatasourceTool` 暴露给 Agent
- Schema 信息从 `information_schema` 动态读取，LLM 需要自己理解表结构

### 问题
1. **接入方式单一** — 无法接入 REST API、CSV、Excel 等常见数据源
2. **语义信息缺失** — LLM 看到的只是字段名，无法理解业务含义
3. **扩展成本高** — 新增数据源类型需要修改 Tool 逻辑

## 设计方案

### 1. 架构概览

```
┌─────────────────────────────────────────────────────┐
│                    Semantic Layer                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ REST API │  │   CSV    │  │ Database │  ...     │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
│       │             │             │                 │
│  ┌────▼─────────────▼─────────────▼────┐           │
│  │      Adapter Registry               │           │
│  │  DatabaseAdapter / RestApiAdapter   │           │
│  │  CsvAdapter / ExcelAdapter          │           │
│  └─────────────────┬───────────────────┘           │
│                    │                                 │
│  ┌─────────────────▼───────────────────┐           │
│  │      Semantic Schema Registry         │           │
│  │  SemanticTableEntity                  │           │
│  │  SemanticFieldEntity                  │           │
│  └───────────────────────────────────────┘           │
└─────────────────────────────────────────────────────┘
```

### 2. 核心实体

#### 2.1 DatasourceEntity 扩展

```java
// 新增字段
String type;           // database | rest_api | csv | excel
String configJson;     // 类型特定的配置（JSON）

// 现有字段保留，database 类型继续使用
String host, port, databaseName, username, password, extraParams, schemaName
```

#### 2.2 SemanticTableEntity（新增）

```java
Long id;
Long datasourceId;           // 关联 DatasourceEntity
String tableName;             // 逻辑表名（API path / 文件名 / DB表名）
String description;           // 表的业务描述
String endpoint;              // 实际端点（API path / 文件路径）
String responsePath;          // JSON 响应中的数据路径（如 data.items）
String relationshipsJson;     // 关联关系 JSON
LocalDateTime createTime;
LocalDateTime updateTime;
```

#### 2.3 SemanticFieldEntity（新增）

```java
Long id;
Long tableId;                 // 关联 SemanticTableEntity
String fieldName;             // 字段名
String type;                  // string | number | boolean | enum | array | object | date | datetime
String description;           // 字段的业务描述
String example;               // 示例值
String unit;                  // 单位（如 °C、m/s、%）
String enumValuesJson;        // 枚举值 JSON（type=enum 时使用）
Boolean searchable;           // 是否支持搜索
String mappingPath;           // 响应 JSON 中的字段路径
```

### 3. 数据源类型适配器

```java
public interface DatasourceAdapter {
    // 获取语义 Schema
    SemanticSchema getSchema(DatasourceEntity entity);

    // 执行查询
    QueryResult execute(DatasourceEntity entity, QueryRequest request);
}

public class DatabaseAdapter implements DatasourceAdapter { ... }
public class RestApiAdapter implements DatasourceAdapter { ... }
public class CsvAdapter implements DatasourceAdapter { ... }
public class ExcelAdapter implements DatasourceAdapter { ... }
```

### 4. API 接口

#### 4.1 数据源管理

```
POST   /api/datasource              # 创建数据源（支持多类型）
GET    /api/datasource              # 列表（脱敏密码）
GET    /api/datasource/{id}        # 详情
PUT    /api/datasource/{id}        # 更新
DELETE /api/datasource/{id}        # 删除
POST   /api/datasource/{id}/test   # 测试连接
PUT    /api/datasource/{id}/toggle # 启用/禁用
```

#### 4.2 语义 Schema 管理

```
POST   /api/datasource/{id}/tables           # 添加语义表
GET    /api/datasource/{id}/tables           # 列出语义表
PUT    /api/datasource/{id}/tables/{tableId} # 更新语义表
DELETE /api/datasource/{id}/tables/{tableId} # 删除语义表

POST   /api/datasource/{id}/tables/{tableId}/fields    # 添加字段
GET    /api/datasource/{id}/tables/{tableId}/fields    # 列出字段
PUT    /api/datasource/{id}/tables/{tableId}/fields/{fieldId} # 更新字段
DELETE /api/datasource/{id}/tables/{tableId}/fields/{fieldId} # 删除字段
```

#### 4.3 统一查询接口

```
POST   /api/datasource/query
{
    "datasourceId": 123,
    "table": "current_weather",
    "filters": [
        { "field": "city_name", "op": "like", "value": "%南京%" }
    ],
    "fields": ["city_name", "temperature_celsius"],
    "sort": { "field": "temperature_celsius", "order": "asc" },
    "limit": 100
}
```

#### 4.4 Schema 发现（供 LLM 使用）

```
GET    /api/datasource/{id}/schema        # 获取完整语义 Schema
GET    /api/datasource/discover            # 发现所有可用表
GET    /api/datasource/{id}/tables/{table}/sample # 获取样本数据
```

### 5. REST API 数据源配置示例

```json
{
    "name": "天气API",
    "type": "rest_api",
    "configJson": {
        "endpoint": "https://api.weather.com/v1",
        "auth": {
            "type": "api_key",
            "headerName": "X-API-Key",
            "key": "xxx"
        },
        "headers": {
            "Accept": "application/json"
        },
        "requestTimeout": 5000
    },
    "semanticTables": [
        {
            "tableName": "current_weather",
            "description": "全球主要城市当前天气状态",
            "endpoint": "/current",
            "method": "GET",
            "responsePath": "data.weather",
            "fields": [
                {
                    "fieldName": "city_name",
                    "type": "string",
                    "description": "城市名称",
                    "example": "上海",
                    "searchable": true,
                    "mappingPath": "city"
                },
                {
                    "fieldName": "temperature_celsius",
                    "type": "number",
                    "description": "当前温度",
                    "unit": "°C",
                    "example": 22,
                    "mappingPath": "temp"
                },
                {
                    "fieldName": "condition_code",
                    "type": "enum",
                    "description": "天气状况代码",
                    "enumValuesJson": {
                        "0": "晴",
                        "1": "多云",
                        "2": "阴",
                        "3": "小雨"
                    },
                    "mappingPath": "condition"
                }
            ]
        }
    ]
}
```

### 6. 数据库类型 Schema 迁移

database 类型的数据源，复用现有 `information_schema` 查询逻辑，同时：
- 允许用户覆盖字段的 description、example、unit 等语义信息
- 支持为已有表添加业务描述（弥补数据库注释缺失的问题）

### 7. 与 Agent 工具集成

扩展现有 `DatasourceTool.query_datasource`：

```java
@Tool(description = "查询配置的数据源...")
public String query_datasource(
    @ToolParam(description = "操作类型: list_datasources | list_tables | describe_table | query") String action,
    @ToolParam(description = "数据源ID") Long datasourceId,
    @ToolParam(description = "表名") String tableName,
    @ToolParam(description = "查询条件（自然语言或结构化）") String query)
```

LLM 通过 `/schema` 接口获取语义信息后，自行构造查询条件调用 `query` 接口。

## 数据库变更

### 新增表

```sql
-- 语义表定义
CREATE TABLE semantic_table (
    id BIGINT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    endpoint VARCHAR(200),
    response_path VARCHAR(200),
    relationships_json TEXT,
    create_time DATETIME,
    update_time DATETIME,
    FOREIGN KEY (datasource_id) REFERENCES datasource(id)
);

-- 语义字段定义
CREATE TABLE semantic_field (
    id BIGINT PRIMARY KEY,
    table_id BIGINT NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    field_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    example VARCHAR(200),
    unit VARCHAR(50),
    enum_values_json TEXT,
    searchable BOOLEAN DEFAULT FALSE,
    mapping_path VARCHAR(200),
    create_time DATETIME,
    update_time DATETIME,
    FOREIGN KEY (table_id) REFERENCES semantic_table(id)
);

-- 为 datasource 表添加 type 和 config_json
ALTER TABLE datasource ADD COLUMN type VARCHAR(50) DEFAULT 'database';
ALTER TABLE datasource ADD COLUMN config_json TEXT;
```

## 实施计划

### Phase 1: 基础框架
- [ ] 扩展 DatasourceEntity 添加 type、configJson 字段
- [ ] 创建 SemanticTableEntity、SemanticFieldEntity
- [ ] 实现 AdapterFactory 和 Adapter 接口
- [ ] 实现 DatabaseAdapter（复用现有逻辑）

### Phase 2: REST API 数据源
- [ ] 实现 RestApiAdapter（WebClient）
- [ ] 实现语义 Schema 的 CRUD API
- [ ] 实现统一查询 API

### Phase 3: CSV/Excel 数据源
- [ ] 实现 CsvAdapter（OpenCSV）
- [ ] 实现 ExcelAdapter（Apache POI）
- [ ] 支持文件上传和自动列推断

### Phase 4: Agent 集成
- [ ] 扩展 DatasourceTool 支持语义查询
- [ ] 实现 Schema 发现接口
- [ ] 添加样本数据查询

## 技术选型

| 组件 | 技术 | 说明 |
|---|---|---|
| HTTP 客户端 | Spring WebClient | 非阻塞，适合 API 调用 |
| CSV 解析 | OpenCSV | 轻量级 CSV 处理 |
| Excel 解析 | Apache POI | 支持 xlsx/xls |
| JSON 处理 | Jackson | 配置序列化 |
| 认证 | 预留扩展点 | api_key / bearer / basic / OAuth2 |

## 预期收益

1. **接入范围扩大** — 支持 REST API、CSV、Excel 等常见数据源
2. **LLM 可读性提升** — 语义层让 LLM 理解字段含义，减少查询错误
3. **用户体验改善** — 用户无需写 SQL，通过自然语言即可查询
4. **代码复用最大化** — Adapter 模式复用现有数据库模块逻辑