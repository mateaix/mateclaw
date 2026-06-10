/** Shared types for the memory module components. */

/** One `## ` section of a memory file (or the synthetic pre-heading preamble). */
export interface MemorySectionData {
  heading: string
  body: string
  userEdited: boolean
  /** True for content before the first `## ` heading — has no addressable key. */
  synthetic?: boolean
}
