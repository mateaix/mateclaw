// ESLint 9 flat config for the Vue 3 + TypeScript admin console.
// Scope: correctness-oriented rules only (vue/essential + typescript-eslint
// recommended). Formatting is left to editors; type safety to vue-tsc.
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import globals from 'globals'

export default tseslint.config(
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'public/**',
      '../mateclaw-server/src/main/resources/static/**',
    ],
  },

  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],

  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        // Delegate <script lang="ts"> blocks to the TypeScript parser
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        sourceType: 'module',
      },
    },
  },

  {
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
  },

  {
    rules: {
      // Views follow a single-word file naming convention (Tools.vue, index.vue)
      'vue/multi-word-component-names': 'off',
      // Pre-existing prop mutations; fix case by case rather than in one sweep
      'vue/no-mutating-props': 'warn',
      // The codebase interfaces with dynamic LLM/tool payloads; `any` is pervasive
      // and vue-tsc already guards real type errors.
      '@typescript-eslint/no-explicit-any': 'off',
      // Warn (not error) on pre-existing dead code; underscore prefix opts out
      '@typescript-eslint/no-unused-vars': [
        'warn',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrors: 'none',
        },
      ],
    },
  },
)
