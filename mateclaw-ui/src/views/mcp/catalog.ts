/**
 * Curated, locally-bundled directory of one-click MCP servers shown in the
 * "Recommended" section of the MCP connections page. Clicking a card opens
 * the create-server modal with these fields pre-filled; credential
 * placeholders (e.g. `YOUR_API_KEY`) are left intact so the user knows
 * what to swap before saving.
 *
 * All HTTP-based remote MCPs use transport = 'streamable_http' (the
 * modern Spring AI default); legacy SSE endpoints can opt into
 * 'sse' explicitly. Stdio entries assume the user has `npx` on PATH.
 */
export interface McpCredentialKey {
  /** Env var or HTTP header name (e.g. CONTEXT7_API_KEY, Authorization). */
  key: string
  /** When true, the server is expected to fail without this key. */
  required: boolean
}

export type McpCatalogConfig =
  | {
      transport: 'streamable_http' | 'sse'
      url: string
      headersJson?: string
    }
  | {
      transport: 'stdio'
      command: string
      argsJson?: string
      envJson?: string
    }

export interface McpCatalogEntry {
  /** Stable slug — used as the default server name. */
  key: string
  /** Display name shown on the card. */
  name: string
  /** One-line capability description. */
  description: string
  /** Official docs URL (opened from the hover external-link icon). */
  docsUrl: string
  /** Pre-fill payload for the create-server form. */
  config: McpCatalogConfig
  /** Which env vars / headers in the config need user-supplied secrets. */
  credentialKeys?: McpCredentialKey[]
  /** Short visual key for the icon-bubble color class. */
  iconKey: string
}

export const mcpCatalog: McpCatalogEntry[] = [
  {
    key: 'context7',
    name: 'Context7',
    description: 'Fetch up-to-date library docs and code examples',
    docsUrl: 'https://github.com/upstash/context7',
    iconKey: 'context7',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.context7.com/mcp',
      headersJson: JSON.stringify({ CONTEXT7_API_KEY: 'YOUR_API_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'CONTEXT7_API_KEY', required: false }],
  },
  {
    key: 'github',
    name: 'GitHub',
    description: 'GitHub Issues, PRs, code search and repository management',
    docsUrl: 'https://github.com/github/github-mcp-server',
    iconKey: 'github',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['-y', '@modelcontextprotocol/server-github']),
      envJson: JSON.stringify(
        { GITHUB_PERSONAL_ACCESS_TOKEN: 'YOUR_TOKEN' },
        null,
        2,
      ),
    },
    credentialKeys: [{ key: 'GITHUB_PERSONAL_ACCESS_TOKEN', required: true }],
  },
  {
    key: 'figma',
    name: 'Figma',
    description: 'Generate diagrams and better code from Figma context',
    docsUrl: 'https://help.figma.com/hc/en-us/articles/32132100833559',
    iconKey: 'figma',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.figma.com/mcp',
    },
  },
  {
    key: 'linear',
    name: 'Linear',
    description: 'Manage issues, projects and team workflows',
    docsUrl: 'https://linear.app/docs/mcp',
    iconKey: 'linear',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.linear.app/mcp',
    },
  },
  {
    key: 'notion',
    name: 'Notion',
    description: 'Search, update and power workflows across your Notion workspace',
    docsUrl: 'https://developers.notion.com/docs/mcp',
    iconKey: 'notion',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.notion.com/mcp',
    },
  },
  {
    key: 'slack',
    name: 'Slack',
    description: 'Send messages, create canvases and fetch Slack data',
    docsUrl: 'https://docs.slack.dev/ai/mcp-server',
    iconKey: 'slack',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.slack.com/mcp',
    },
  },
  {
    key: 'supabase',
    name: 'Supabase',
    description: 'Manage databases, authentication and storage',
    docsUrl: 'https://supabase.com/docs/guides/getting-started/mcp',
    iconKey: 'supabase',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.supabase.com/mcp',
    },
  },
  {
    key: 'vercel',
    name: 'Vercel',
    description: 'Analyze, debug and manage projects and deployments',
    docsUrl: 'https://vercel.com/docs/mcp/vercel-mcp',
    iconKey: 'vercel',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.vercel.com',
    },
  },
  {
    key: 'sentry',
    name: 'Sentry',
    description: 'Search, query and debug errors intelligently',
    docsUrl: 'https://docs.sentry.io/product/sentry-mcp/',
    iconKey: 'sentry',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.sentry.dev/mcp',
      headersJson: JSON.stringify({ SENTRY_ACCESS_TOKEN: 'YOUR_ACCESS_TOKEN' }, null, 2),
    },
    credentialKeys: [{ key: 'SENTRY_ACCESS_TOKEN', required: true }],
  },
  {
    key: 'stripe',
    name: 'Stripe',
    description: 'Payment processing and financial infrastructure tools',
    docsUrl: 'https://docs.stripe.com/mcp',
    iconKey: 'stripe',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.stripe.com',
      headersJson: JSON.stringify({ STRIPE_SECRET_KEY: 'YOUR_SECRET_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'STRIPE_SECRET_KEY', required: true }],
  },
  {
    key: 'atlassian',
    name: 'Atlassian',
    description: 'Access Jira and Confluence from your agent',
    docsUrl: 'https://www.atlassian.com/platform/remote-mcp-server',
    iconKey: 'atlassian',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.atlassian.com/v1/mcp',
    },
  },
  {
    key: 'cloudflare',
    name: 'Cloudflare',
    description: 'Build with compute, storage and AI on Cloudflare',
    docsUrl: 'https://developers.cloudflare.com/agents/model-context-protocol/',
    iconKey: 'cloudflare',
    config: {
      transport: 'streamable_http',
      url: 'https://bindings.mcp.cloudflare.com/mcp',
    },
  },
  {
    key: 'huggingface',
    name: 'Hugging Face',
    description: 'Access the Hugging Face Hub and thousands of Gradio apps',
    docsUrl: 'https://huggingface.co/settings/mcp',
    iconKey: 'hugging_face',
    config: {
      transport: 'streamable_http',
      url: 'https://huggingface.co/mcp',
    },
  },
  {
    key: 'posthog',
    name: 'PostHog',
    description: 'Query, analyze and manage your PostHog insights',
    docsUrl: 'https://posthog.com/docs/model-context-protocol',
    iconKey: 'posthog',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.posthog.com/mcp',
    },
  },
  {
    key: 'playwright',
    name: 'Playwright',
    description: 'Browser automation with Playwright',
    docsUrl: 'https://github.com/microsoft/playwright-mcp',
    iconKey: 'playwright',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['@playwright/mcp@latest']),
    },
  },
  {
    key: 'chrome-devtools',
    name: 'Chrome DevTools',
    description: 'Browser debugging and performance analysis with Chrome DevTools',
    docsUrl: 'https://github.com/ChromeDevTools/chrome-devtools-mcp',
    iconKey: 'chrome_devtools',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['chrome-devtools-mcp@latest']),
    },
  },
  {
    key: 'exa',
    name: 'Exa',
    description: 'Web search and code context retrieval powered by Exa AI',
    docsUrl: 'https://docs.exa.ai/reference/exa-mcp',
    iconKey: 'exa',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['-y', 'exa-mcp-server', 'tools=web_search_exa,get_code_context_exa']),
      envJson: JSON.stringify({ EXA_API_KEY: 'YOUR_API_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'EXA_API_KEY', required: true }],
  },
]

/** Two-letter abbreviation used in the fallback icon bubble. */
export function catalogInitial(name: string): string {
  const trimmed = name.trim()
  if (!trimmed) return '?'
  // Take leading non-space chunks, prefer first letter of each.
  const parts = trimmed.split(/[\s-_/]+/).filter(Boolean)
  if (parts.length >= 2) {
    return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase()
  }
  return trimmed.slice(0, 2).toUpperCase()
}
