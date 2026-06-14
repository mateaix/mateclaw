-- Register 4 collaboration skills introduced in RFC-044.
-- These were previously only in seed data files; this migration ensures they exist
-- in all environments (including existing installs that have already run seed data).
-- Ref: rfc-044-skill-md-completion-2026-04-23

INSERT INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
VALUES (1000000016, 'make_plan',
        '当任务需要多步拆解或不确定执行路径时，向更强 Agent 请求一份分步可落地的执行计划，由当前 Agent 自己执行。',
        'builtin', '🗺️', '1.3.0', 'MateClaw',
        '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
        1, 1, 'plan,delegate,agent,collaboration', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET description  = EXCLUDED.description,
    skill_type   = EXCLUDED.skill_type,
    icon         = EXCLUDED.icon,
    version      = EXCLUDED.version,
    author       = EXCLUDED.author,
    config_json  = EXCLUDED.config_json,
    enabled      = EXCLUDED.enabled,
    builtin      = EXCLUDED.builtin,
    tags         = EXCLUDED.tags,
    update_time  = EXCLUDED.update_time,
    deleted      = EXCLUDED.deleted;

INSERT INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
VALUES (1000000017, 'chat_with_agent',
        '当需要咨询其他 Agent、寻求帮助或用户明确要求某个 Agent 参与时，使用本技能进行单次或并行委托。',
        'builtin', '💬', '1.2.0', 'MateClaw',
        '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
        1, 1, 'agent,chat,collaborate,delegate', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET description  = EXCLUDED.description,
    skill_type   = EXCLUDED.skill_type,
    icon         = EXCLUDED.icon,
    version      = EXCLUDED.version,
    author       = EXCLUDED.author,
    config_json  = EXCLUDED.config_json,
    enabled      = EXCLUDED.enabled,
    builtin      = EXCLUDED.builtin,
    tags         = EXCLUDED.tags,
    update_time  = EXCLUDED.update_time,
    deleted      = EXCLUDED.deleted;

INSERT INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
VALUES (1000000018, 'channel_message',
        '当需要主动向用户、会话或渠道单向推送消息时使用。任务完成通知、定时提醒、异步结果回推等场景。',
        'builtin', '📤', '1.3.0', 'MateClaw',
        '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
        1, 1, 'channel,message,push,notify,dingtalk,feishu', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET description  = EXCLUDED.description,
    skill_type   = EXCLUDED.skill_type,
    icon         = EXCLUDED.icon,
    version      = EXCLUDED.version,
    author       = EXCLUDED.author,
    config_json  = EXCLUDED.config_json,
    enabled      = EXCLUDED.enabled,
    builtin      = EXCLUDED.builtin,
    tags         = EXCLUDED.tags,
    update_time  = EXCLUDED.update_time,
    deleted      = EXCLUDED.deleted;

INSERT INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
VALUES (1000000019, 'multi_agent_collaboration',
        '当任务需要多个 Agent 的专业能力协同完成时，编排多 Agent 并行或串行协作，整合各方结果。',
        'builtin', '🤝', '1.4.0', 'MateClaw',
        '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
        1, 1, 'multi-agent,collaboration,orchestration,parallel', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET description  = EXCLUDED.description,
    skill_type   = EXCLUDED.skill_type,
    icon         = EXCLUDED.icon,
    version      = EXCLUDED.version,
    author       = EXCLUDED.author,
    config_json  = EXCLUDED.config_json,
    enabled      = EXCLUDED.enabled,
    builtin      = EXCLUDED.builtin,
    tags         = EXCLUDED.tags,
    update_time  = EXCLUDED.update_time,
    deleted      = EXCLUDED.deleted;
