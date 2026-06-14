-- V54: Localize seeded channel names to Chinese for zh-CN installs.
-- See h2/V54 for the full rationale. Same logic, MySQL-flavored.
-- Idempotent: re-running finds no matching rows after the first apply.

UPDATE mate_channel SET name = 'Web 控制台'
 WHERE id = 1000000001 AND name = 'Web Console'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '钉钉机器人'
 WHERE id = 1000000002 AND name = 'DingTalk Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '飞书机器人'
 WHERE id = 1000000003 AND name = 'Feishu Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'Telegram 机器人'
 WHERE id = 1000000004 AND name = 'Telegram Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'Discord 机器人'
 WHERE id = 1000000005 AND name = 'Discord Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '企业微信机器人'
 WHERE id = 1000000006 AND name = 'WeCom Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'QQ 机器人'
 WHERE id = 1000000007 AND name = 'QQ Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

-- id 1000000008 ("微信") already in Chinese; intentionally skipped.

UPDATE mate_channel SET name = 'Slack 机器人'
 WHERE id = 1000000009 AND name = 'Slack Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');
