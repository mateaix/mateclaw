/** Reprocessing resets the material before a new job exists. The latest
 * stored job can still describe the previous run and must not appear current. */
export function isCurrentRawJob(
  materialStatus: string,
  job: { status: string } | undefined,
): boolean {
  if (!job) return false
  const materialActive = materialStatus === 'pending' || materialStatus === 'processing'
  const jobTerminal = ['completed', 'failed', 'partial', 'cancelled'].includes(job.status)
  return !(materialActive && jobTerminal)
}
