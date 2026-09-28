// 与后端 DTO 对应的类型定义

export interface FileEntry {
  id: number
  path: string
  size: number
  mtime: string
  mimeType: string | null
  category: string | null
}

export interface DuplicateGroup {
  id: number
  hash: string
  status: 'SUSPECTED' | 'CONFIRMED'
  totalSize: number
  members: FileEntry[]
}

export interface Condition {
  field: 'TYPE' | 'SIZE_BYTES' | 'AGE_DAYS' | 'NAME_REGEX_FIELD' | 'SRC_PREFIX'
  op: 'EQ' | 'NEQ' | 'GT' | 'GTE' | 'LT' | 'LTE' | 'MATCHES'
  value: string
}

export interface RuleAction {
  type: 'MOVE' | 'RENAME' | 'TAG'
  targetTemplate: string | null
  tag: string | null
}

export interface Rule {
  id: number
  name: string
  priority: number
  enabled: boolean
  conditions: Condition[]
  action: RuleAction
}

export interface Operation {
  id: number
  type: 'MOVE' | 'RENAME' | 'DELETE_TO_QUARANTINE'
  src: string
  dst: string | null
  expectedSize: number
  expectedMtime: string | null
  status: 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED'
  error: string | null
}

export interface Plan {
  id: number
  status: 'PLANNED' | 'EXECUTING' | 'EXECUTED' | 'FAILED' | 'ROLLED_BACK' | 'NEEDS_REVIEW'
  description: string | null
  error: string | null
  operations: Operation[]
}

export interface PlanRow {
  id: number
  status: Plan['status']
  description: string | null
  error: string | null
  createdAt: string
  executedAt: string | null
  rolledBackAt: string | null
}

export interface ScanRow {
  id: number
  rootPath: string
  status: 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED'
  statsJson: string | null
  startedAt: string
  finishedAt: string | null
}

export interface QuarantineItem {
  id: number
  originalPath: string
  quarantinePath: string
  size: number
  hash: string | null
  quarantinedAt: string
  purgeAfter: string
  purged: boolean
}

export interface CreatePlanRequest {
  kind: 'DUPLICATE_CLEANUP' | 'RULE_APPLY'
  refId: number
  keepStrategy?: 'NEWEST' | 'OLDEST' | 'FIRST_PATH'
  targetDir?: string
}

export interface Dashboard {
  totalScans: number
  latestScan: { id: number; rootPath: string } | null
  indexedFiles: number
  indexedBytes: number
  categories: Record<string, number>
  duplicateGroups: number
  reclaimableBytes: number
  plans: Record<string, number>
  quarantine: { items: number; bytes: number; expired: number }
}

export interface WatchStatus {
  dir: string
  active: boolean
}

