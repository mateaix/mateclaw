/**
 * electron-builder.cjs — Dynamic build configuration.
 *
 * Two packaging modes are controlled by the BUILD_MODE environment variable:
 *
 *   BUILD_MODE=local  (default)  Full build: bundles the embedded JRE and
 *                                Spring Boot JAR so the desktop app can run a
 *                                local backend.  Original behavior.
 *
 *   BUILD_MODE=remote            Lightweight build: omits the JRE/JAR
 *                                resources (~530 MB smaller on macOS).  The app
 *                                can only connect to a remote server — the
 *                                "local" connection option is hidden in the
 *                                splash UI.
 *
 * Branding is controlled by branding.config.json or BRAND_* env vars.
 * See scripts/branding.cjs for details.
 *
 * Usage:
 *   BUILD_MODE=remote npx electron-builder --mac
 *   npm run package:mac:remote
 *   BRAND_NAME=MyAI npm run package:mac:remote
 */
'use strict'

const { loadBrandConfig } = require('./scripts/branding.cjs')

const mode = process.env.BUILD_MODE === 'remote' ? 'remote' : 'local'
const brand = loadBrandConfig(__dirname)

// Derive a short slug from the brand name for artifact file names.
// "MyAI" → "MyAI", "Cool App" → "Cool_App"
const brandSlug = brand.name.replace(/\s+/g, '_')

// Parse GitHub URL for publish config (owner/repo)
let githubOwner = 'matevip'
let githubRepo = 'mateclaw'
const ghMatch = brand.githubUrl.match(/github\.com\/([^/]+)\/([^/]+)/)
if (ghMatch) {
  githubOwner = ghMatch[1]
  githubRepo = ghMatch[2]
}

/** @type {import('electron-builder').Configuration} */
const config = {
  appId: brand.appId,
  productName: brand.name,
  copyright: brand.copyright,
  directories: { output: 'release' },
  publish: [
    {
      provider: 'github',
      owner: githubOwner,
      repo: githubRepo,
    },
  ],
  files: ['dist-electron', 'dist'],
  afterPack: 'scripts/trim-playwright-driver.cjs',

  // extraResources: only bundle JRE + JAR in local mode.
  // In remote mode this array is empty — the packaged app contains only the
  // Electron + Vue shell, cutting ~530 MB from the installer.
  extraResources:
    mode === 'local'
      ? [
          {
            from: 'resources/jre/${os}-${arch}/',
            to: 'jre/',
            filter: ['**/*'],
          },
          {
            from: 'resources/app.jar',
            to: 'app.jar',
          },
        ]
      : [],

  mac: {
    category: 'public.app-category.productivity',
    target: [
      { target: 'dmg', arch: ['arm64', 'x64'] },
      { target: 'zip', arch: ['arm64', 'x64'] },
    ],
    icon: 'build/icon.icns',
    hardenedRuntime: true,
    gatekeeperAssess: false,
    entitlements: 'build/entitlements.mac.plist',
    entitlementsInherit: 'build/entitlements.mac.inherit.plist',
    // Differentiate installers so users can tell local vs remote builds apart.
    artifactName:
      mode === 'remote'
        ? `${brandSlug}_Remote_${'$'}{version}_${'$'}{arch}.${'$'}{ext}`
        : `${brandSlug}_${'$'}{version}_${'$'}{arch}.${'$'}{ext}`,
  },

  dmg: {
    contents: [
      { x: 130, y: 220 },
      { x: 410, y: 220, type: 'link', path: '/Applications' },
    ],
    title: `${brand.name} ${'$'}{version}`,
  },

  win: {
    target: [
      { target: 'nsis', arch: 'x64' },
      { target: 'nsis', arch: 'arm64' },
    ],
    icon: 'build/icon.ico',
    artifactName:
      mode === 'remote'
        ? `${brandSlug}_Remote_${'$'}{version}_${'$'}{arch}_Setup.${'$'}{ext}`
        : `${brandSlug}_${'$'}{version}_${'$'}{arch}_Setup.${'$'}{ext}`,
  },

  nsis: {
    oneClick: false,
    perMachine: false,
    allowToChangeInstallationDirectory: true,
    deleteAppDataOnUninstall: false,
    installerIcon: 'build/icon.ico',
    uninstallerIcon: 'build/icon.ico',
    installerHeaderIcon: 'build/icon.ico',
    createDesktopShortcut: true,
    createStartMenuShortcut: true,
  },

  linux: {
    target: ['AppImage'],
    icon: 'build/icon.png',
    category: 'Utility',
    artifactName:
      mode === 'remote'
        ? `${brandSlug}_Remote_${'$'}{version}.${'$'}{ext}`
        : `${brandSlug}_${'$'}{version}.${'$'}{ext}`,
  },
}

module.exports = config
