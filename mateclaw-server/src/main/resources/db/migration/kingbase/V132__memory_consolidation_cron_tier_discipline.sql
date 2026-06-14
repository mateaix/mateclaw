-- Update the daily "memory consolidation" cron prompt on existing databases so it
-- keeps project-specific volatile facts (codenames, tech stacks, per-project
-- decisions) out of the always-on MEMORY.md. Seed scripts only run on fresh
-- installs, so existing rows need this data migration to pick up the new wording.
-- Scoped to the original default text so user-edited prompts are left untouched.

UPDATE mate_cron_job
SET trigger_message = '请回顾你最近的 memory/ 日记文件，将反复出现的重要信息（用户偏好、稳定事实、经验教训、工作流）提炼整合到 MEMORY.md 中。注意：MEMORY.md 会被注入每一次对话，只整合跨项目长期稳定的信息；具体项目的代号、名称、技术栈、仓库、单项目的指标/预算/团队/上线日期或只对某个项目成立的决策等易变事实，不要写入 MEMORY.md（会随项目切换互相冲突、导致张冠李戴），应留在 daily note 或通过结构化 project 记忆维护。判定口诀：换一个项目后仍成立才进 MEMORY.md。保留日记原文不动，只更新 MEMORY.md。完成后简要说明做了哪些整合。'
WHERE trigger_message = '请回顾你最近的 memory/ 日记文件，将反复出现的重要信息（用户偏好、稳定事实、经验教训、工作流）提炼整合到 MEMORY.md 中。保留日记原文不动，只更新 MEMORY.md。完成后简要说明做了哪些整合。';

UPDATE mate_cron_job
SET trigger_message = 'Review your recent memory/ daily note files and consolidate recurring important information (user preferences, stable facts, lessons learned, workflows) into MEMORY.md. Note: MEMORY.md is injected into every conversation, so only consolidate cross-project, long-term stable information; do NOT write project-specific volatile facts into MEMORY.md (project codenames, names, tech stacks, repos, a single project''s metrics/budget/team/launch date, or decisions that hold only for one project) — they conflict across projects and cause mix-ups. Keep those in the daily note or maintain them via structured project memory. Rule of thumb: only facts that still hold after switching projects belong in MEMORY.md. Keep the original daily notes intact, only update MEMORY.md. Briefly describe what consolidations were made.'
WHERE trigger_message = 'Review your recent memory/ daily note files and consolidate recurring important information (user preferences, stable facts, lessons learned, workflows) into MEMORY.md. Keep the original daily notes intact, only update MEMORY.md. Briefly describe what consolidations were made.';
