// Provider brand icon resolution. Icon assets live in public/icons/providers/.
// Shared by the Models settings page and the dashboard model-config card so the
// id → icon mapping has a single source of truth.

const providerIconMap: Record<string, string> = {
  'dashscope': '/icons/providers/dashscope.png',
  // dashscope-compat shares the same Aliyun DashScope brand — same logo.
  'dashscope-compat': '/icons/providers/dashscope.png',
  'modelscope': '/icons/providers/modelscope.svg',
  'aliyun-codingplan': '/icons/providers/aliyun-codingplan.svg',
  'aliyun-codingplan-intl': '/icons/providers/aliyun-codingplan.svg',
  // bailian-team is an Aliyun product line — reuse the aliyun mark.
  'bailian-team': '/icons/providers/aliyun-codingplan.svg',
  'openai': '/icons/providers/openai.svg',
  'azure-openai': '/icons/providers/azure-openai.svg',
  'minimax': '/icons/providers/minimax.png',
  'minimax-cn': '/icons/providers/minimax.png',
  'kimi-cn': '/icons/providers/kimi.svg',
  'kimi-intl': '/icons/providers/kimi.svg',
  'kimi-code': '/icons/providers/kimi.svg',
  'deepseek': '/icons/providers/deepseek.svg',
  'anthropic': '/icons/providers/anthropic.svg',
  'gemini': '/icons/providers/gemini.svg',
  'ollama': '/icons/providers/ollama.svg',
  'lmstudio': '/icons/providers/lmstudio.svg',
  'llamacpp': '/icons/providers/llamacpp.svg',
  'mlx': '/icons/providers/mlx.svg',
  'openrouter': '/icons/providers/openrouter.svg',
  'zhipu-cn': '/icons/providers/zhipu.svg',
  'zhipu-intl': '/icons/providers/zhipu.svg',
  // Coding Plan subscription endpoints — same brand, reuse mark.
  'zhipu-cn-codingplan': '/icons/providers/zhipu.svg',
  'zhipu-intl-codingplan': '/icons/providers/zhipu.svg',
  'volcengine': '/icons/providers/volcengine.svg',
  // volcengine-plan = "Volcano Engine Coding Plan" — same brand, reuse mark.
  'volcengine-plan': '/icons/providers/volcengine.svg',
  'xiaomi-mimo': '/icons/providers/xiaomimimo.svg',
  'hunyuan-3d': '/icons/providers/hunyuan-color.svg',
  'opencode': '/icons/providers/opencode.svg',
  'siliconflow-cn': '/icons/providers/siliconcloud.svg',
  'siliconflow-intl': '/icons/providers/siliconcloud.svg',
  'openai-chatgpt': '/icons/providers/openai.svg',
  'anthropic-claude-code': '/icons/providers/anthropic.svg',
}

export function getProviderIcon(providerId: string): string {
  return providerIconMap[providerId] || '/icons/providers/default.svg'
}

// Hide the <img> when the brand icon fails to load so the UI falls back cleanly.
export function onProviderIconError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}
