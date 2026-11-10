// 展示层通用格式化与共享映射

/** ISO 时间 → 本地可读时间（原始 ISO 带纳秒和时区，不适合直接展示）。 */
export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function fmtSize(bytes: number): string {
  if (!Number.isFinite(bytes)) return '—'
  if (bytes >= 1 << 30) return (bytes / (1 << 30)).toFixed(2) + ' GB'
  if (bytes >= 1 << 20) return (bytes / (1 << 20)).toFixed(2) + ' MB'
  if (bytes >= 1 << 10) return (bytes / (1 << 10)).toFixed(1) + ' KB'
  return bytes + ' B'
}

/** 文件类别 → 中文名（仪表盘统计与规则条件共用，键与后端 Category 枚举一致）。 */
export const CATEGORY_LABELS: Record<string, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频',
  DOCUMENT: '文档',
  ARCHIVE: '压缩包',
  CODE: '代码',
  OTHER: '其他'
}

/** 重复组保留策略，与后端 KeepStrategy 枚举一一对应。 */
export type KeepStrategy = 'NEWEST' | 'OLDEST' | 'FIRST_PATH'

export const KEEP_STRATEGY_LABELS: Record<KeepStrategy, string> = {
  NEWEST: '保留最新',
  OLDEST: '保留最旧',
  FIRST_PATH: '保留路径最靠前'
}

// Java String.compareTo 按 UTF-16 码元序比较，与 JS 的 < / > 一致，
// 这里刻意不用 localeCompare，保证并列副本的决胜结果和后端完全相同。
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

type Member = { path: string; mtime: string | null }

/** 按策略计算重复组中将保留的副本（并列时与后端 KeepStrategy 的路径决胜规则一致）。 */
export function pickKept(members: Member[], strategy: KeepStrategy): Member | undefined {
  if (members.length === 0) return undefined
  if (strategy === 'FIRST_PATH') {
    return [...members].sort((a, b) => cmp(a.path, b.path))[0]
  }
  const byMtime = (a: Member, b: Member) => cmp(a.mtime ?? '', b.mtime ?? '') || cmp(a.path, b.path)
  const sorted = [...members].sort(byMtime)
  return strategy === 'OLDEST' ? sorted[0] : sorted[sorted.length - 1]
}
