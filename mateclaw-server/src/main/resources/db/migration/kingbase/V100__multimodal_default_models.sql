-- V100: System-level defaults for vision and video sidecar routing.
-- When the agent's primary model lacks the modality required by an attachment,
-- the runtime delegates a single caption call to the model recorded here.
-- Empty value = not configured; the UI then asks the user to pick one.
-- Setting value stores mate_model_config.id as a string (provider+model_name pairs are not unique).
INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
VALUES (1000002001, 'default.vision_model', '',
        'Default vision-capable model id (mate_model_config.id) used by sidecar router when primary model lacks VISION modality',
        NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET setting_key = EXCLUDED.setting_key;

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
VALUES (1000002002, 'default.video_model', '',
        'Default video-capable model id (mate_model_config.id) used by sidecar router when primary model lacks VIDEO modality',
        NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET setting_key = EXCLUDED.setting_key;
