/**
 * Lazy registrar for the <model-viewer> Web Component.
 *
 * @google/model-viewer is a heavy dependency (~hundreds of KB) that used to be
 * imported eagerly in main.ts, landing it in the initial entry chunk on every
 * first paint even though 3D (.glb) previews are rare. This defers the import to
 * the first time a chat bubble actually renders a 3D attachment. The module-level
 * promise makes it a process-wide singleton — the component registers exactly
 * once no matter how many bubbles request it.
 *
 * Vue's compiler still treats <model-viewer> as a custom element via the
 * isCustomElement option in vite.config.ts, so the tag resolves whether or not
 * the definition has loaded yet; once this import resolves the element upgrades.
 */
let loadPromise: Promise<unknown> | null = null

export function ensureModelViewer(): Promise<unknown> {
  if (!loadPromise) {
    loadPromise = import('@google/model-viewer')
  }
  return loadPromise
}
