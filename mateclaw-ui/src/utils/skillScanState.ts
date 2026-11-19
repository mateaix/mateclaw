/** Scan completion is separate from whether inspected code matched a dangerous pattern. */
export function hasIncompleteSkillScan(findings: unknown): boolean {
  return Array.isArray(findings) && findings.some(value => value !== null
    && typeof value === 'object' && value.category === 'SCAN_INCOMPLETE')
}
