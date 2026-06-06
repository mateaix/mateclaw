---
name: guidance
version: "1.2.1"
description: "回答用户关于 MateClaw 安装与配置的问题。优先定位并阅读本地文档，再提炼答案；文档不足时访问官网。"
dependencies:
  tools:
    - readMateClawDoc
    - read_file
    - web_search
---

# MateClaw 安装与配置问答

当用户询问 MateClaw 的安装、初始化、环境配置、依赖要求、常见配置项时使用本技能。

**核心原则**：先查文档，再回答；不臆测；回答语言与提问语言一致。

## 工作流程

### 第一步：查文档目录

```
readMateClawDoc(action="list")
```

浏览返回的文件列表，找到与用户问题最相关的文档（如 `zh/quickstart.md`、`en/config.md`）。

### 第二步：读取相关文档

```
readMateClawDoc(action="read", path="zh/quickstart.md")
```

文档较长时只读相关章节；如多个文档都相关，按优先级依次读取。

### 第三步：提炼答案

从文档中提取关键信息，组织成可执行答案：
1. 先给直接结论
2. 再给步骤 / 命令 / 配置示例
3. 补充必要前置条件和常见坑

### 第四步（兜底）：搜索官网

如本地文档信息不足：
```
web_search(query="MateClaw 安装配置 <关键词>", language="zh-CN", count=5)
```

参考搜索结果补充回答，并注明信息来自官网搜索。

## 文档路径速查

| 内容 | 路径 |
|------|------|
| 快速开始（中文） | `zh/quickstart.md` |
| 配置说明（中文） | `zh/config.md` |
| Quick Start (EN) | `en/quickstart.md` |
| Config Reference (EN) | `en/config.md` |

## 输出质量要求

- 不编造不存在的配置项或命令
- 涉及版本差异时标注"请以当前版本文档为准"
- 涉及路径、命令、配置键时给可复制的原文片段
- 若信息仍不足，明确告知用户缺少哪类信息（操作系统、安装方式、报错日志等）
