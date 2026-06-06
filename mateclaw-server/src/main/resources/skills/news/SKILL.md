---
name: news
version: "2.0.1"
description: "从互联网查询最新新闻。支持政治、财经、社会、国际、科技、体育、娱乐等分类，自动适配搜索工具与浏览器工具。"
dependencies:
  tools:
    - web_search
    - browser_use
---

# 新闻查询

当用户询问"最新新闻"、"今天发生了什么"或某类别新闻时使用本技能。

## 工作流程

### 方式一：搜索工具（推荐，速度快）

```
web_search(
  query="今日财经新闻",
  freshness="day",
  language="zh-CN",
  count=8
)
```

参数说明：
- `freshness`：`day`（24h）/ `week` / `month` / `year`
- `language`：`zh-CN`（中文）/ `en`（英文）
- `count`：返回结果数，1-10，默认 5

### 方式二：浏览器直接访问权威来源

当搜索结果质量不佳或用户需要更权威来源时：

```
browser_use(action="start")
browser_use(action="open", url="https://www.chinanews.com/society/")
browser_use(action="snapshot")
```

| 类别 | 来源 | URL |
|------|------|-----|
| 政治 | 人民网 · 党报 | https://cpc.people.com.cn/ |
| 财经 | 中国经济网 | http://www.ce.cn/ |
| 社会 | 中新网 · 社会 | https://www.chinanews.com/society/ |
| 国际 | CGTN | https://www.cgtn.com/ |
| 科技 | 科技日报 | https://www.stdaily.com/ |
| 体育 | 央视体育 | https://sports.cctv.com/ |
| 娱乐 | 新浪娱乐 | https://ent.sina.com.cn/ |

浏览器用完后关闭：
```
browser_use(action="stop")
```

## 输出格式

以要点列表呈现，每条包含：
- 标题（加粗）
- 一两句摘要
- 来源 + 发布时间

示例：
```
**经济数据：3 月 CPI 同比上涨 0.1%**
国家统计局今日发布数据，环比下降 0.4%，低于市场预期。
来源：中国经济网 · 2026-04-23
```

## 方式三：持续监控 RSS/Atom（blogwatcher）

当用户需要**定期追踪**某个来源而非单次查询时，使用 `blogwatcher-cli`。

安装：`pip install blogwatcher-cli`（SQLite 后端，无需服务器）

```bash
# 添加 RSS 源
blogwatcher add --name "科技日报" --url https://www.stdaily.com/rss.xml

# 添加 Atom 源（也支持）
blogwatcher add --name "MIT Tech Review" --url https://www.technologyreview.com/feed/

# 列出已订阅来源
blogwatcher list

# 拉取所有来源的新文章（增量，只返回未见过的）
blogwatcher fetch --all

# 拉取指定来源
blogwatcher fetch --name "科技日报"

# 查看最近 N 条条目
blogwatcher entries --limit 20

# 搜索历史条目
blogwatcher search "人工智能 大模型"

# 删除来源
blogwatcher remove --name "科技日报"
```

`blogwatcher fetch` 是**增量**的：只返回自上次 fetch 以来的新条目，不重复推送旧内容。

**适用场景**：
- 用户说"每天帮我看看某网站有什么新文章"
- 需要追踪多个来源、避免重复浏览
- 配合 `cron` 技能设置定时抓取任务

## 注意事项

- 搜索结果以新鲜度为优先，优先选 `freshness="day"`
- 多个类别时分别搜索，避免混淆
- 网站无法访问时说明原因并提供备用来源链接
- 不编造新闻，所有内容来自实际搜索/浏览结果
