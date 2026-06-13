-- V72: Register Tencent Hunyuan 3D model variants. See h2/V72 for full rationale.

INSERT INTO mate_model_config (
    id, name, provider, model_name, description,
    temperature, max_tokens, top_p, builtin, enabled, is_default,
    model_type, create_time, update_time, deleted
) VALUES
    (1000000500, 'HY-3D-3.1', 'hunyuan-3d', 'HY-3D-3.1',
     '腾讯混元 3D 3.1 — 最高精度，支持 PBR / 多视角 / Geometry 白模等专业参数',
     NULL, NULL, NULL, 1, 1, 1, 'model3d', NOW(), NOW(), 0),
    (1000000501, 'HY-3D-3.0', 'hunyuan-3d', 'HY-3D-3.0',
     '腾讯混元 3D 3.0 — 老一代 Pro 模型，与 3.1 共享 SubmitHunyuanTo3DProJob 调用',
     NULL, NULL, NULL, 1, 1, 0, 'model3d', NOW(), NOW(), 0),
    (1000000502, 'HY-3D-Express', 'hunyuan-3d', 'HY-3D-Express',
     '腾讯混元 3D 极速版 — 走 SubmitHunyuanTo3DRapidJob 接口，速度最快但仅支持 Prompt / ImageUrl',
     NULL, NULL, NULL, 1, 1, 0, 'model3d', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET
    name        = EXCLUDED.name,
    model_name  = EXCLUDED.model_name,
    description = EXCLUDED.description,
    builtin     = EXCLUDED.builtin,
    enabled     = EXCLUDED.enabled,
    is_default  = EXCLUDED.is_default,
    model_type  = EXCLUDED.model_type,
    update_time = NOW();
