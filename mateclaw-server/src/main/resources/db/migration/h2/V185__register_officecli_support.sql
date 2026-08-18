-- Issue #583 / RFC-048: optional iOfficeAI/OfficeCLI advanced Office engine.
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000029, 'OfficeCliTool', 'OfficeCLI Advanced Documents', 'Inspect, validate, copy-edit, merge, and render DOCX/XLSX/PPTX through the optional iOfficeAI/OfficeCLI binary. Mutations are copy-on-write and return generated-file links.', 'builtin', 'officeCliTool', '🏢', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000020, 'officecli', 'Use optional iOfficeAI/OfficeCLI for advanced inspection, validation, copy editing, template merge, and visual rendering of existing DOCX/XLSX/PPTX files.', 'builtin', '🏢', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'office,officecli,docx,xlsx,pptx,render,validate', NOW(), NOW(), 0);

UPDATE mate_skill SET name_zh = 'OfficeCLI 高级文档', name_en = 'OfficeCLI Advanced Documents' WHERE name = 'officecli';
