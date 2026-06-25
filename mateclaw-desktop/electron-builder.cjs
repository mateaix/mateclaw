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
 * Usage:
 *   BUILD_MODE=remote npx electron-builder --mac
 *   npm run package:mac:remote
 */
'use strict'

const mode = process.env.BUILD_MODE === 'remote' ? 'remote' : 'local'

/** @type {import('electron-builder').Configuration} */
const config = {
  appId: 'vip.mate.mateclaw',
  productName: 'MateClaw',
  copyright: 'Copyright © 2026 MateClaw Team',
  directories: { output: 'release' },
  publish: [
    {
      provider: 'github',
      owner: 'matevip',
      repo: 'mateclaw',
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
        ? 'MateClaw_Remote_${version}_${arch}.${ext}'
        : 'MateClaw_${version}_${arch}.${ext}',
  },

  dmg: {
    contents: [
      { x: 130, y: 220 },
      { x: 410, y: 220, type: 'link', path: '/Applications' },
    ],
    title: 'MateClaw ${version}',
  },

  win: {
    target: [
      { target: 'nsis', arch: 'x64' },
      { target: 'nsis', arch: 'arm64' },
    ],
    icon: 'build/icon.ico',
    artifactName:
      mode === 'remote'
        ? 'MateClaw_Remote_${version}_${arch}_Setup.${ext}'
        : 'MateClaw_${version}_${arch}_Setup.${ext}',
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
        ? 'MateClaw_Remote_${version}.${ext}'
        : 'MateClaw_${version}.${ext}',
  },
}

module.exports = config
