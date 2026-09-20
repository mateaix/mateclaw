export type SkillFileBucket = 'references' | 'templates' | 'scripts'

/** Keep the selected folder and all descendants under the chosen skill bucket. */
export function planSkillFileUploads(files: File[], bucket: SkillFileBucket) {
  if (files.length > 100 || files.reduce((n, file) => n + file.size, 0) > 50 * 1024 * 1024) {
    throw new Error('batchLimit')
  }
  const paths = new Set<string>()
  return files.map(file => {
    if (file.size > 10 * 1024 * 1024) throw new Error('fileLimit')
    const relative = file.webkitRelativePath || file.name
    const path = `${bucket}/${relative}`
    if (path.length > 512 || relative.includes('..') || /[\\:\x00-\x1f\x7f]/.test(relative)
        || relative.split('/').some(part => !part || part === '.') || paths.has(path)) {
      throw new Error('invalidPath')
    }
    paths.add(path)
    return { file, path }
  })
}
