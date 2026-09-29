// 展示层通用格式化

/** ISO 时间 → 本地可读时间（原始 ISO 带纳秒和时区，不适合直接展示）。 */
export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function fmtSize(bytes: number): string {
  if (bytes > 1 << 30) return (bytes / (1 << 30)).toFixed(2) + ' GB'
  if (bytes > 1 << 20) return (bytes / (1 << 20)).toFixed(2) + ' MB'
  if (bytes > 1 << 10) return (bytes / (1 << 10)).toFixed(1) + ' KB'
  return bytes + ' B'
}
