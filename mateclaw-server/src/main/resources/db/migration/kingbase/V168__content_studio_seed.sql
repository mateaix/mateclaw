-- V168: Content Studio scenario seed (公众号 / 小红书 图文创作).
-- Delivers to EXISTING databases the rows fresh installs get from db/data-*.sql:
-- built-in tools (wechat_article_extract, gzh_publish, xhs_publish, gzh_package,
-- capture_screenshot, xhs_package), the 内容工作室 (Content Studio) agent, and two disabled
-- cron templates. DatabaseBootstrapRunner skips seeding once a database is
-- initialized, so these would otherwise never reach upgraders. Idempotent upsert
-- on id; content is the default (zh-CN) locale — a fresh install re-runs the
-- locale seed afterwards.


INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000630, 'WechatArticleExtractTool', '公众号文章抓取', '抓取微信公众号文章：输入文章 URL，返回清洗后的标题/作者/时间/正文(Markdown)/图片。用于「参考公众号信息抓取汇总」，比 browser_use 更适合 mp.weixin.qq.com 文章页。', 'builtin', 'wechatArticleExtractTool', '📰', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000631, 'GzhPublishTool', '公众号发布', '将生成的图文发布到微信公众号：action=draft 上传封面并存入草稿箱（推荐）；action=publish 为认证号群发，需显式确认。需在系统设置配置 weixinoa.app_id / weixinoa.app_secret。', 'builtin', 'gzhPublishTool', '📤', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000632, 'XhsPublishTool', '小红书发布打包', '把小红书笔记（文案+标签+卡片图）打包成一个可下载 zip，并给出创作平台手动上传步骤。小红书无官方发布 API，不自动上传、不绕过风控。', 'builtin', 'xhsPublishTool', '📕', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000633, 'GzhPackageTool', '公众号打包', '把公众号成稿（Markdown）打包成在线预览（渲染 HTML）+ 素材下载 zip（article.html/article.md/封面）。服务端生成内联样式 HTML，避免大段 HTML 作为工具参数被截断而失败。', 'builtin', 'gzhPackageTool', '📦', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000634, 'ScreenshotTool', '后台截图', '截取 MateClaw 后台页面（站内相对路径如 /chat、/channels）并返回可嵌入的图片 URL。用于给「如何用 MateClaw 做 XX」这类操作教程配真实产品截图，把返回 URL 以 ![](url) 嵌进 gzh_package 的 Markdown。', 'builtin', 'screenshotTool', '📷', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000635, 'XhsPackageTool', '小红书打包', '把小红书笔记打包成在线预览（手机版滑动预览，以图为主、文字辅助）+ 素材下载 zip（编号卡片图 + 文案.txt）。强制至少 3 张竖版图（1 封面 + ≥2 内容图），不足则拒绝打包。小红书无发布 API，不自动上传。', 'builtin', 'xhsPackageTool', '🖼️', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_agent (id, name, description, agent_type, system_prompt, model_name, max_iterations, enabled, icon, tags, create_time, update_time, deleted)
VALUES (1000000640, '内容工作室', '端到端创作公众号与小红书图文：选题搜集、成文、配图、去AI化、排版、入草稿箱发布。', 'react', '你是 MateClaw 的「内容工作室」——专门端到端创作微信公众号（公众号）与小红书图文。

工作流（7 段）：
1）选题——读取 topic_interests 记忆并用 web_search(freshness=week) 找角度。
2）搜集——对用户给的公众号参考链接用 wechat_article_extract（或 browser_use）抓取并汇总，保持原创并标注来源，严禁照搬洗稿。
3）成文——公众号加载 gzh_article 技能、小红书加载 xhs_note 技能，遵循用户人设与文风。
4）配图——用 image_generate 出封面/配图，用 render_html_image 把小红书卡片 HTML 渲染成图。
5）去AI化——加载 deai_humanize 技能，跑「检测→改写」循环直到 AI 味评分足够低。
6）打包交付——用 gzh_package 交付：传 Markdown 正文，服务端生成公众号内联样式 HTML + 在线预览 + 素材下载；切勿把大段内联 HTML 塞进 write_file 或 render_html_image(html=...)（大参数会被截断导致失败）。
7）发布——把 gzh_package 的在线预览发给用户，确认后默认 gzh_publish action=draft 存入草稿箱。

每次任务开始先 recall_structured 这些键并遵循：content_persona、writing_style_gzh、writing_style_xhs、topic_interests、banned_words、signature_blocks。若缺少必要项，向用户询问一次并 remember_structured。

发布是外向且不可逆的动作：调用 gzh_publish 前必须展示最终内容并获得用户明确确认；未经 confirmPublish=true 与用户认可，绝不群发。遵守 banned_words 与广告法限制；所有内容保持原创。
', NULL, 100, TRUE, 'pi:pen-nib', 'content,gzh,xhs,writing', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, agent_type=EXCLUDED.agent_type, system_prompt=EXCLUDED.system_prompt, model_name=EXCLUDED.model_name, max_iterations=EXCLUDED.max_iterations, enabled=EXCLUDED.enabled, icon=EXCLUDED.icon, tags=EXCLUDED.tags, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
VALUES (1000100020, '每日选题雷达', '0 8 * * *', 'Asia/Shanghai', 1000000640, 'agent', NULL, '读取结构化记忆 topic_interests，用 web_search（freshness=week）搜集与这些方向相关的今日热点与新鲜角度，产出一份「今日选题清单」：每条含选题标题、一句话切入角度、目标平台（公众号/小红书）、推荐配图方向。只做选题，不成文。', FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, cron_expression=EXCLUDED.cron_expression, timezone=EXCLUDED.timezone, agent_id=EXCLUDED.agent_id, task_type=EXCLUDED.task_type, trigger_message=EXCLUDED.trigger_message, request_body=EXCLUDED.request_body, enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
VALUES (1000100021, '每周公众号入草稿箱', '0 9 * * 1', 'Asia/Shanghai', 1000000640, 'agent', NULL, '从 topic_interests 里挑一个当周选题，加载 gzh_article 技能完成一篇公众号图文（含配图与去AI化），排版为内联样式 HTML。若已在系统设置配置公众号凭证（weixinoa.app_id/app_secret），用 gzh_publish action=draft 存入草稿箱并提醒我去后台核对发表；未配置则直接把排版 HTML 与封面发我。', FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, cron_expression=EXCLUDED.cron_expression, timezone=EXCLUDED.timezone, agent_id=EXCLUDED.agent_id, task_type=EXCLUDED.task_type, trigger_message=EXCLUDED.trigger_message, request_body=EXCLUDED.request_body, enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
