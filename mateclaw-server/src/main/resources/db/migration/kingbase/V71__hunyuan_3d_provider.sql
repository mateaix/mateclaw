-- V71: Register Tencent Hunyuan 3D provider for ai3d service.
-- See h2/V71 for full rationale.

INSERT INTO mate_model_provider (
    provider_id, name, api_key_prefix, chat_model, api_key, base_url,
    generate_kwargs, is_custom, is_local, support_model_discovery,
    support_connection_check, freeze_url, require_api_key, auth_type,
    create_time, update_time
) VALUES (
    'hunyuan-3d', '腾讯混元 3D', 'AKID', 'NotApplicable', '',
    'https://ai3d.tencentcloudapi.com',
    '{"service":"ai3d","version":"2025-05-13","region":"ap-guangzhou"}',
    0, 0, 0, 0, 1, 1, 'tc3_hmac_sha256',
    NOW(), NOW()
)
ON CONFLICT (provider_id) DO UPDATE SET
    name             = EXCLUDED.name,
    api_key_prefix   = EXCLUDED.api_key_prefix,
    chat_model       = EXCLUDED.chat_model,
    base_url         = EXCLUDED.base_url,
    generate_kwargs  = EXCLUDED.generate_kwargs,
    is_custom        = EXCLUDED.is_custom,
    is_local         = EXCLUDED.is_local,
    support_model_discovery = EXCLUDED.support_model_discovery,
    support_connection_check = EXCLUDED.support_connection_check,
    freeze_url       = EXCLUDED.freeze_url,
    require_api_key  = EXCLUDED.require_api_key,
    auth_type        = EXCLUDED.auth_type,
    update_time      = NOW();
