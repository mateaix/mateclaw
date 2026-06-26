// afterPack hook: no-op placeholder
// The original trim-playwright-driver.cjs was not included in the open-source release.
// This no-op allows electron-builder to complete packaging.
exports.default = async function () {
  // intentionally empty
}
