const trimDriver = require('./trim-playwright-driver.cjs')
const { signAppJar } = require('./sign-jar-natives.cjs')
const path = require('node:path')

module.exports = async function afterPack(context) {
  if (process.env.BUILD_MODE === 'remote') return
  await trimDriver(context)
  if (context.electronPlatformName !== 'darwin') return

  // Use electron-builder's identity discovery, including its temporary CI keychain.
  const { findIdentity, isSignAllowed } = require('app-builder-lib/out/codeSign/macCodeSign')
  const options = context.packager.platformSpecificBuildOptions
  if (!isSignAllowed(false) || options.identity === null) return
  const { keychainFile } = await context.packager.codeSigningInfo.value
  const identity = await findIdentity('Developer ID Application', options.identity, keychainFile)
  if (!identity) {
    if (context.packager.forceCodeSigning) throw new Error('No Developer ID Application identity for JAR signing')
    console.log('[sign-jar-natives] No Developer ID Application identity; skipping nested signing')
    return
  }
  const appJar = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`, 'Contents', 'Resources', 'app.jar')
  const count = await signAppJar(appJar, {
    identity: identity.hash || identity.name,
    keychain: keychainFile,
    entitlements: path.join(context.packager.projectDir, 'build/entitlements.mac.inherit.plist'),
  })
  console.log(`[sign-jar-natives] Signed and verified ${count} Mach-O binaries inside app.jar`)
}
