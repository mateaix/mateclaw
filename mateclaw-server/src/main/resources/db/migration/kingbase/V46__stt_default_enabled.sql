-- Default-enable STT on existing deployments. See the h2/ counterpart for
-- the "why enabled by default" rationale and the bug history that drove
-- the skip-if-exists idiom. Same V number is used in h2/ for cross-dialect
-- parity.
--
-- MySQL doesn't allow INSERT ... SELECT ... WHERE NOT EXISTS without a
-- FROM clause, so we synthesise one with FROM DUAL. The end result is
-- the same: insert when the setting_key is absent, no-op when it's already
-- there.

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000020, 'sttEnabled', 'true', 'Enable speech-to-text (TalkMode mic input)', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttEnabled');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000021, 'sttProvider', 'auto', 'STT provider: auto / openai / dashscope', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttProvider');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000022, 'sttFallbackEnabled', 'true', 'Try alternate STT provider when the primary fails', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttFallbackEnabled');
