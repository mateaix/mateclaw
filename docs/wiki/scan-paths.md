# 知识库扫描路径配置：多路径与 Glob 通配符

> 适用版本：v1.5.0+

---

## 概述

知识库的**扫描路径**（原称"源目录"）支持配置多条路径，每条路径可以是：

- **普通目录路径** — 递归扫描目录内全部支持格式的文件
- **Glob 通配符模式** — 精确控制扫描哪些子目录和文件类型

配置入口：**知识库 → 高级管理 → 变更监测 → 扫描路径**。

---

## 配置格式

每行写一条路径或模式，以 `#` 开头的行视为注释：

```
# 这是注释，会被忽略

/data/manuals
/data/ocr-output/**/*.txt
/data/reports/*.{xlsx,csv}
```

**规则**：
- 每行一条，支持任意条数
- 路径必须是**绝对路径**（以 `/` 开头）
- 空行忽略，`#` 注释行忽略
- 两条模式覆盖同一个文件时，该文件只处理一次（以第一条匹配为准）

---

## 两种模式详解

### 模式一：普通目录路径

```
/data/docs
```

行为与旧版"源目录"完全相同：

- 递归遍历该目录的所有子目录（跳过以 `.` 开头的隐藏目录）
- 只摄取以下格式：`txt` `md` `csv` `pdf` `docx` `doc` `pptx` `ppt` `xlsx` `xls` `html` `htm`

---

### 模式二：Glob 通配符模式

```
/data/ocr-output/**/*.txt
```

行为：

1. 自动提取通配符前的固定路径作为扫描根（上例为 `/data/ocr-output`）
2. 从该目录出发递归遍历
3. 对每个文件的**绝对路径**做模式匹配，只保留命中的文件

**关键区别**：当模式的文件名段**明确指定了扩展名**（如 `*.txt`、`*.{xlsx,csv}`），系统会跳过格式白名单的二次过滤，完全按模式结果为准。这意味着你可以用通配符精确控制摄取哪些类型。

---

## 通配符语法参考

| 符号 | 含义 | 示例 | 匹配 |
|---|---|---|---|
| `*` | 匹配当前目录层级内的任意字符（不跨 `/`） | `*.txt` | `report.txt`，但不匹配 `a/b.txt` |
| `**` | 匹配任意层级的目录（可跨 `/`） | `**/ocr/*.txt` | `2024/ocr/a.txt`、`raw/ocr/b.txt` |
| `?` | 匹配单个任意字符 | `report?.txt` | `report1.txt`、`reportA.txt` |
| `{a,b}` | 匹配括号内列举的任一选项 | `*.{xlsx,csv}` | `data.xlsx`、`data.csv` |
| `[abc]` | 匹配括号内的任一字符 | `file[123].md` | `file1.md`、`file2.md` |

> **注意**：`*` 不匹配路径分隔符 `/`，要跨目录必须用 `**`。

---

## 典型场景示例

### 场景一：OCR 文档库

目录结构：
```
/data/ocr/
├── 2024/
│   ├── scan_001.pdf   ← 原始扫描件，不需要
│   └── scan_001.txt   ← OCR 转换结果，需要摄取
├── 2025/
│   ├── scan_002.pdf
│   └── scan_002.txt
```

配置：
```
# 只摄取 OCR 转换后的文本，忽略原始 PDF 扫描件
/data/ocr/**/*.txt
```

效果：`scan_001.txt`、`scan_002.txt` 被摄取；`.pdf` 文件被忽略。

---

### 场景二：多数据源合并

```
# 产品手册（Markdown）
/data/product-manuals

# 季度报告（Excel/CSV，只要报告目录，不要子目录里的草稿）
/data/reports/*.{xlsx,csv}

# 法律合规文件（PDF）
/archive/legal/**/*.pdf
```

---

### 场景三：按年份过滤

```
# 只处理 2024 和 2025 年的归档
/data/archive/2024
/data/archive/2025
```

---

### 场景四：排除特定子目录

Glob 本身不支持"排除"语法，但可以通过多条精确路径绕过：

```
# 目标：扫描 /data/wiki 下除 drafts/ 之外的所有 Markdown
# 方法：列出需要的子目录
/data/wiki/published/**/*.md
/data/wiki/reviewed/**/*.md
/data/wiki/archived/**/*.md
```

---

## 安全说明

### 路径验证

每条路径/模式的固定前缀都经过安全验证：

- 通配符前的目录部分必须通过 `mate.wiki.allowed-source-roots` 白名单校验（若已配置）
- 符号链接（symlink）目标必须在各自的扫描根目录内，不允许逃逸

### 服务器部署推荐

生产/多租户部署时，建议在 `application.yml` 中锁定允许的根目录：

```yaml
mate:
  wiki:
    allowed-source-roots:
      - /data/knowledge
      - /mnt/shared-docs
    require-allowed-roots: true   # 未配置根目录时拒绝所有路径
```

Docker 部署时等价的环境变量：

```env
MATE_WIKI_ALLOWED_SOURCE_ROOTS=/data/knowledge,/mnt/shared-docs
MATE_WIKI_REQUIRE_ALLOWED_ROOTS=true
```

---

## 常见问题

### Q：配置保存后扫描没有结果，怎么排查？

1. 点击「立即扫描」，查看返回的 `errors` 字段
2. 确认路径是服务器上的**绝对路径**，不是宿主机路径（Docker 内路径取决于卷挂载）
3. 确认 Glob 前缀目录存在（例如 `/data/ocr-output` 必须已存在）
4. 检查日志关键词：`[Wiki] Scan completed`，确认 `patterns` 列表与配置一致

### Q：扫描计数里 `scanned` 比预期多，为什么？

查看日志中的 `errors` 列表，可能有"Scan limit reached"警告。默认最多扫描 500 个文件，可在配置中调整：

```yaml
mate:
  wiki:
    max-scan-files: 2000
```

### Q：能不能用相对路径？

不能。所有路径必须是绝对路径（以 `/` 开头）。

### Q：`{` 或 `[` 出现在路径中时报错怎么办？

这些字符是 Glob 保留字符，如果实际路径名中包含它们，暂时只能用普通目录路径模式（即不含通配符的完整路径）来覆盖该目录。

### Q：Windows 路径支持吗？

桌面版（Electron）运行在 Windows 时，路径应使用反斜杠或正斜杠均可，但 Glob 模式建议使用正斜杠。服务器部署通常是 Linux，使用正斜杠。

---

## 参数速查

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `mate.wiki.max-scan-files` | `500` | 单次扫描最多采集的文件数上限 |
| `mate.wiki.max-scan-file-size` | `52428800`（50 MB） | 超过此大小的文件被跳过 |
| `mate.wiki.allowed-source-roots` | `[]`（空，不限制） | 允许的根目录白名单 |
| `mate.wiki.require-allowed-roots` | `false` | 为空时是否拒绝所有路径（服务器推荐 `true`） |
| `mate.wiki.watcher-enabled` | `false` | 是否启用定时自动扫描 |
| `mate.wiki.watcher-interval-ms` | `300000`（5 分钟） | 自动扫描间隔 |
