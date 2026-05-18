import { ref } from 'vue'

/**
 * Drag-and-drop file upload helper.
 *
 * Tracks the hover state of a drop zone and hands the drop event to the
 * caller. The tricky part it encapsulates is the nested-element flicker:
 * `dragenter` / `dragleave` fire for every child the pointer crosses, so a
 * naive boolean flag flickers off mid-drag. A depth counter fixes that —
 * `isDragging` only clears once every entered element has been left.
 *
 * Wire the handlers onto the drop zone and bind `isDragging` for the visual
 * overlay:
 *
 * ```vue
 * <div
 *   @dragenter.prevent="onDragEnter"
 *   @dragover.prevent
 *   @dragleave.prevent="onDragLeave"
 *   @drop.prevent="onDrop"
 *   :class="{ 'is-dragging': isDragging }"
 * />
 * ```
 *
 * The drop payload is intentionally not pre-parsed — callers extract whatever
 * they need from the {@link DragEvent} (`dataTransfer.files`, `.items` for
 * directory entries, etc.).
 *
 * @param onDrop called with the raw drop event after the drag state is reset.
 */
export function useFileDrop(onDrop: (e: DragEvent) => void) {
  const isDragging = ref(false)
  let dragCounter = 0

  function onDragEnter(e: DragEvent) {
    dragCounter++
    // Only react to actual file drags — ignore text or in-page element drags.
    if (e.dataTransfer?.types?.includes('Files')) isDragging.value = true
  }

  function onDragLeave() {
    dragCounter--
    if (dragCounter <= 0) {
      dragCounter = 0
      isDragging.value = false
    }
  }

  function handleDrop(e: DragEvent) {
    dragCounter = 0
    isDragging.value = false
    onDrop(e)
  }

  return { isDragging, onDragEnter, onDragLeave, onDrop: handleDrop }
}
