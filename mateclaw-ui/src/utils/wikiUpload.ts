export const WIKI_UPLOAD_TIMEOUT_MS = 5 * 60 * 1000

export const WIKI_UPLOAD_CONCURRENCY = 2

export async function processWithConcurrency<T>(
  items: readonly T[],
  worker: (item: T, index: number) => Promise<void>,
  concurrency = WIKI_UPLOAD_CONCURRENCY,
): Promise<void> {
  if (!Number.isInteger(concurrency) || concurrency < 1) {
    throw new RangeError('concurrency must be a positive integer')
  }

  const iterator = items.entries()
  async function runWorker() {
    for (const [index, item] of iterator) {
      await worker(item, index)
    }
  }

  const workerCount = Math.min(concurrency, items.length)
  await Promise.all(Array.from({ length: workerCount }, () => runWorker()))
}
