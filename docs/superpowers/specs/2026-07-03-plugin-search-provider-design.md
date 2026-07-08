# 插件化搜索 Provider + 搜索设置页重构 设计文档

日期：2026-07-03
状态：待评审
相关：`vip.mate.tool.search`（现有搜索 provider 链）、`mateclaw-plugin-api`（插件 SDK）、`/settings/system` 搜索设置区块

## 1. 背景与问题

### 1.1 自定义搜索 provider 没有插件化路径

当前 `SearchProviderRegistry` 通过 Spring 构造器注入 `List<SearchProvider>` 收集 provider，只认同一 `ApplicationContext` 里的 bean。要新增一个搜索源，唯一办法是**在 `vip.mate.tool.search` 源码树里加 `@Component` 类并重新编译部署整个 server**。

而项目已有一套真正的运行时插件系统（`mateclaw-plugin-api` + `PluginManager`）：独立 jar 丢进 `~/.mateclaw/plugins/` 或工作区 `plugins/`，`URLClassLoader` 隔离加载，支持运行时 enable/disable，配置走 manifest 声明的 schema（`mateclaw-plugin.json` 的 `config` 字段）+ `plugin` 表 `config_json` 持久化 + `PUT /api/v1/plugins/{name}/config` 接口。但 `PluginType` 只有 `TOOL / PROVIDER(LLM) / CHANNEL / MEMORY` 四类，**没有 SEARCH**，`PluginContext` 也没有对应注册方法。

LLM provider 已有"内置 `@Component` 链 + 插件注册表"双轨并存的先例（`ModelProviderService.pluginChatModels`），搜索 provider 缺的就是同构的第二轨。

### 1.2 搜索设置 UI 平铺、下拉菜单硬编码

`/settings/system` 的搜索区块把 4 个 provider 的开关/key/url 共 9 个配置项拍平在一个列表里；主 provider 下拉菜单是写死的两个 `<option>`（serper/tavily），`searxng`/`duckduckgo` 无法显式选中，只能靠后端自动探测兜底；管理员也无法看到"当前实际生效的是哪个 provider"。

### 1.3 插件配置表单缺失（前端）

后端 `PluginInfo` 已返回 `configSchema`（来自 manifest）和脱敏后的 `currentConfig`，`updateConfig()` 已有 schema 白名单 + required 校验，前端 `pluginApi.updateConfig` 客户端也已存在——但 `Plugins.vue` 没有任何配置编辑 UI，这条链路在前端是死代码。所有类型的插件目前都无法在界面上配置。

## 2. 目标 / 非目标

**目标**
1. 第三方以独立 jar 形式提供搜索 provider：实现 SDK 接口 + manifest 声明，丢进 plugins 目录即用，**mateclaw-server 源码零改动**。
2. 搜索设置页：主 provider 选择动态化（含插件 provider 与"自动选择"）、按 provider 分组折叠、显示当前实际生效的 provider。
3. 补上 schema 驱动的插件配置表单（服务所有插件类型，不只 search）。

**非目标**
- 不改内置 4 个 provider 的配置存储方式（继续走 `SystemSettingsDTO` / `mate_system_setting`）。
- 不删除、不重命名 `GET/PUT /api/v1/settings` 现有字段（无破坏性改动）。
- 不做搜索结果聚合/多 provider 并发查询。

## 3. 设计

### 3.1 SDK 侧（`mateclaw-plugin-api`）

新增 `vip.mate.plugin.api.search` 包，接口**不依赖任何 server 类**（jar 隔离加载下的硬约束；对比核心 `SearchProvider` 依赖 `SystemSettingsDTO`，SDK 版必须自包含）：

```java
public interface PluginSearchProvider {
    String id();                                      // 全局唯一，如 "my-search"
    String label();                                   // 显示名
    default boolean requiresCredential() { return true; }
    default int autoDetectOrder() { return 500; }     // 默认排在内置 provider（50~400）之后
    boolean isAvailable();                            // 插件自查：如 context.getConfig 拿 key 判空
    List<PluginSearchResult> search(PluginSearchQuery query);
}

public record PluginSearchQuery(String query, String freshness, String language, Integer count) {}
public record PluginSearchResult(String title, String url, String snippet, String source, String date) {}
```

- `PluginType` 增加 `SEARCH`。
- `PluginContext` 增加 `void registerSearchProvider(PluginSearchProvider provider);`。
  （接口新增方法对已编译的存量插件无影响——它们不调用即可。）
- 插件的配置（API key 等）**不进搜索设置页**，走插件系统自己的机制：manifest `config` 声明 schema，运行时 `context.getConfig(key, type)` 读取。职责天然分离：搜索设置页只管"选谁"，插件页管"配它"。

### 3.2 Server 桥接侧

**`bridge/PluginSearchBridge.java`**（模式照抄 `PluginChannelBridge`）：把 `PluginSearchProvider` 适配成核心 `SearchProvider`：
- `search(SearchQuery, SystemSettingsDTO)` → 转调插件 `search(PluginSearchQuery)`，忽略 DTO；
- 结果转核心 `SearchResult`，`providerId` 填插件 provider id；
- `isAvailable(SystemSettingsDTO)` → 委托插件无参 `isAvailable()`；
- 插件抛出的异常原样上抛（`WebSearchService.tryProvider()` 已有 catch-and-fallback 语义）。

**`SearchProviderRegistry` 可变化**：从"构造时定死的 immutable list"改为两层合并视图：
- 基底：Spring 注入的内置 provider（不变）；
- 插件区：`ConcurrentHashMap<String, SearchProvider>`，新增 `registerPluginProvider(SearchProvider)` / `unregisterPluginProvider(String id)`；
- `allSorted()` / `getById()` / `resolve()` 全部查合并视图，排序仍按 `autoDetectOrder`；
- **id 冲突拒绝注册**（插件 id 与内置或已注册插件 id 重复时抛 `PluginException`，不允许顶掉 serper 等内置项）。

**生命周期**（与现有四类完全对称）：
- `PluginContextImpl.registerSearchProvider()` → 包 bridge 后调 registry 注册，记录到 `LoadedPlugin`；
- `disablePlugin()` 与加载失败 rollback 路径各加一个 `searchProviderRegistry.unregisterPluginProvider(...)`（best-effort，同现有风格）；
- 插件被 disable 后，若它正是 `searchProvider` 显式指定项，`resolve()` 因 `getById()` 查不到而自动落入 auto-detect 分支——行为安全，无需额外处理。

### 3.3 动态 provider catalog 接口

`GET /api/v1/settings/search-providers`（`SystemSettingController`，`@RequireWorkspaceRole("admin")`），只读：

```json
{
  "providers": [
    { "id": "serper",   "label": "Serper (Google)", "builtin": true,  "requiresCredential": true,  "available": false },
    { "id": "my-search","label": "My Search",       "builtin": false, "requiresCredential": true,  "available": true,
      "pluginName": "my-search-plugin" }
  ],
  "resolved": { "id": "my-search", "source": "configured" }
}
```

- 数据源：`SearchProviderRegistry.allSorted()`（合并视图，插件 provider 自动出现）+ `resolve(config)`（暴露"当前实际生效"与原因：`configured` / `auto-detect` / `keyless-fallback`）。
- `pluginName` 供前端渲染"去插件页配置"跳转。
- 不含任何敏感值。

### 3.4 搜索设置页重构（`views/Settings/System/index.vue`）

- **主 provider 选择**：选项从 catalog 接口动态渲染，新增首项"自动选择（推荐）"——对应 `searchProvider=""`（后端 `resolve()` 对空值本就走 auto-detect，无需引入 `"auto"` 特殊值）。下方常驻一行状态提示：`✓ 当前实际生效: Xxx（原因）`。
- **分组折叠卡片**：每个 provider 一张可折叠卡片，标题行 = 名称 + 徽标（已配置/未配置/生效中），默认只展开"当前生效"的那张。
  - 内置 provider：卡片内是现有的 key/url 输入框（字段与保存逻辑不变，仍走 `PUT /api/v1/settings`）；
  - 插件 provider：卡片内不放表单，显示"该 Provider 由插件 {pluginName} 提供，请在插件页配置" + 跳转链接。
- 现有保存语义不变（API key 仅在用户输入新值时提交）。

### 3.5 插件配置表单（`views/Plugins.vue`，纯前端）

插件卡片增加"配置"入口（有 `configSchema` 时显示），弹出 schema 驱动的通用表单：
- 按 `configSchema` 渲染字段：`secret=true` → password 输入框（placeholder 显示脱敏值，留空表示不修改）；其余按 `type` 渲染 text/number/boolean；`required` 标星并做前端必填校验（后端已有兜底校验）；`description` 作为字段提示。
- 提交走已存在的 `pluginApi.updateConfig`；保存后刷新列表。
- 该表单对所有 `PluginType` 通用，非 search 专属。
- 注意：manifest `ConfigField.type` 是自由字符串，前端对未知 type 一律降级为 text 输入。

### 3.6 参考实现（`mateclaw-plugin-sample`）

sample 模块增加一个最小 `PluginSearchProvider` 实现（如包装一个可配 baseUrl+apiKey 的通用 HTTP 搜索 API），manifest 声明 `type: "search"` + config schema——同时充当文档示例与集成测试素材。

## 4. 交付拆分（遵循上游单一关注点规范）

- **上游 issue 先行**：动手前在 mateaix/mateclaw 提 issue 说明设计（本文档摘要），获认可后实施。
- **PR-1（后端 + SDK）**：`PluginType.SEARCH` + SDK 接口/record + `PluginSearchBridge` + registry 可变化 + `PluginContextImpl`/`PluginManager` 生命周期 + sample 参考实现 + 单测。
- **PR-2（接口 + 前端）**：catalog 接口 + 搜索设置页分组折叠重构 + Plugins.vue schema 配置表单。PR-2 不依赖 PR-1 合并（catalog 对纯内置 provider 同样成立），但先后合并时插件 provider 自动出现在下拉中。

## 5. 测试

**PR-1**
- registry：注册/反注册/合并排序/`resolve()` 三分支含插件项/id 冲突拒绝。
- bridge：`SearchQuery`↔`PluginSearchQuery`、`SearchResult` 转换、异常透传。
- 生命周期：disable 后 registry 查不到该 id；显式指定的插件 provider 被 disable 后 resolve 落回 auto-detect。
- sample 插件 jar 端到端：打包 → 放插件目录 → 启动加载 → `getAllToolCallbacks` 路径外单独验证 `web_search` 走插件 provider。

**PR-2**
- catalog 接口：内置/插件混合列表、resolved 三种 source、无敏感值泄露。
- 前端：下拉动态渲染、"自动选择"存空串、折叠展开状态、secret 字段留空不覆盖。

## 6. 兼容性与风险

- 存量插件：`PluginType` 加枚举值 + `PluginContext` 加方法，均为增量，不影响已编译插件。
- `GET/PUT /api/v1/settings` 字段不动，旧前端/脚本不受影响。
- `SearchProviderRegistry` 由不可变转可变：并发读多写少，`ConcurrentHashMap` + 每次读时合并排序（provider 总数 <10，无性能顾虑）。
- 插件 provider 质量不可控：`WebSearchService` 现有 15s 超时属于各 provider 自身实现，插件侧超时由插件自负；catch-and-fallback 链保证坏插件不拖垮搜索功能（最多浪费一次尝试）。
- 安全：插件 jar 本身即任意代码执行（现有插件系统的既定信任模型，本设计不扩大攻击面）；catalog 接口仅 admin 可见。
