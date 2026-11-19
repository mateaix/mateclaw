/** Keep list snapshots and an already-open drawer in sync without changing selection. */
export function applySkillSnapshot<T extends { id: string | number }>(
  updated: T, segments: { items: T[] }[], detail: { value: T | null },
): void {
  const sameId = (value: T) => String(value.id) === String(updated.id)
  if (detail.value && sameId(detail.value)) detail.value = { ...detail.value, ...updated }
  for (const segment of segments) {
    const index = segment.items.findIndex(sameId)
    if (index >= 0) segment.items.splice(index, 1, { ...segment.items[index], ...updated })
  }
}
