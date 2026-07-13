import type { ChatAttachment } from '@/types'

/**
 * Minimal window-event bus that lets any part of the app open the single
 * global {@link FilePreviewDialog} without prop-drilling a ref. Used by chat
 * attachment cards and by the global generated-file link interceptor
 * (useGlobalFileDownloadClick) so AI-generated docx/xlsx/pdf links preview
 * in-place instead of downloading.
 */
export const OPEN_FILE_PREVIEW_EVENT = 'mateclaw:open-file-preview'

/** The subset of {@link ChatAttachment} a preview needs. */
export type PreviewTarget = Pick<ChatAttachment, 'name' | 'url'>
  & Partial<Pick<ChatAttachment, 'contentType' | 'storedName' | 'size' | 'path'>>

export function openFilePreview(target: PreviewTarget): void {
  window.dispatchEvent(new CustomEvent<PreviewTarget>(OPEN_FILE_PREVIEW_EVENT, { detail: target }))
}
