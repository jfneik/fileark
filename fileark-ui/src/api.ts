import axios from 'axios'
import type {
  CreatePlanRequest, Dashboard, DuplicateGroup, FileEntry, Plan, PlanRow, QuarantineItem, Rule, ScanRow, WatchStatus
} from './types'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

// 统一取后端错误体 {code, message, detail}
export function errText(e: unknown): string {
  const resp = (e as { response?: { data?: { message?: string; code?: string } } }).response?.data
  return resp?.message ?? String(e)
}

export const api = {
  // 扫描
  createScan: (rootPath: string) => http.post<{ scanId: number }>('/scans', { rootPath }),
  listScans: () => http.get<ScanRow[]>('/scans'),
  cancelScan: (id: number) => http.post(`/scans/${id}/cancel`),
  duplicates: (id: number, status?: string) =>
    http.get<DuplicateGroup[]>(`/scans/${id}/duplicates`, { params: status ? { status } : {} }),

  // 规则
  rules: () => http.get<Rule[]>('/rules'),
  createRule: (r: Rule) => http.post<Rule>('/rules', r),
  updateRule: (r: Rule) => http.put<Rule>(`/rules/${r.id}`, r),
  deleteRule: (id: number) => http.delete(`/rules/${id}`),

  // 计划
  dryRun: (req: CreatePlanRequest) => http.post<Plan>('/plans/dry-run', req),
  createPlan: (req: CreatePlanRequest) => http.post<Plan>('/plans', req),
  plans: () => http.get<PlanRow[]>('/plans'),
  plan: (id: number) => http.get<Plan>(`/plans/${id}`),
  execute: (id: number) => http.post<Plan>(`/plans/${id}/execute`),
  rollback: (id: number) => http.post<Plan>(`/plans/${id}/rollback`),
  ackReview: (id: number) => http.post<Plan>(`/plans/${id}/ack-review`),

  // 安全区与设置
  quarantine: () => http.get<QuarantineItem[]>('/quarantine'),
  restore: (id: number) => http.post(`/quarantine/${id}/restore`),
  purge: (id: number) => http.delete(`/quarantine/${id}`, { params: { confirm: true } }),
  purgeExpired: () => http.post('/quarantine/purge-expired', null, { params: { confirm: true } }),
  settings: () => http.get<Record<string, string>>('/settings'),
  saveSettings: (kv: Record<string, string>) => http.put<Record<string, string>>('/settings', kv),
  deleteSetting: (key: string) => http.delete<Record<string, string>>(`/settings/${encodeURIComponent(key)}`),

  // 相似图片与缩略图
  similar: (scanId: number, threshold: number) =>
    http.get<FileEntry[][]>(`/scans/${scanId}/similar`, { params: { threshold } }),
  thumbnailUrl: (fileId: number) => `/api/files/${fileId}/thumbnail`,

  // 仪表盘与目录监控
  dashboard: () => http.get<Dashboard>('/dashboard'),
  watchStatus: () => http.get<{ active: WatchStatus[]; rulesEnabled: boolean }>('/watch'),
  startWatch: (dir: string) => http.post('/watch', { dir }),
  stopWatch: (dir: string) => http.delete('/watch', { params: { dir } })
}
