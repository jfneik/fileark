<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import { fmtSize, fmtTime } from '../utils'
import type { DuplicateGroup, Plan, ScanRow } from '../types'

const rootPath = ref('')
const scans = ref<ScanRow[]>([])
const selectedScanId = ref<number>()
const groups = ref<DuplicateGroup[]>([])
const progress = ref('')
const keepChoice = ref<Record<number, number>>({}) // groupId -> 保留的 fileEntryId
const preview = ref<Plan | null>(null)
const previewGroupId = ref<number>()
const previewVisible = ref(false)
const quickDirs = ['~/Downloads', '~/Desktop', '~/Documents']

async function refreshScans() {
  scans.value = (await api.listScans()).data
  if (selectedScanId.value == null && scans.value.length > 0) {
    await selectScan(scans.value[0].id)
  }
}

function startScan() {
  if (!rootPath.value) {
    ElMessage.warning('请输入要扫描的目录')
    return
  }
  launchScan(rootPath.value)
}

function rescan(row: ScanRow) {
  rootPath.value = row.rootPath
  launchScan(row.rootPath)
}

function launchScan(dir: string) {
  api.createScan(dir).then(({ data }) => {
    ElMessage.success(`扫描 #${data.scanId} 已启动`)
    const es = new EventSource(`/api/scans/${data.scanId}/events`)
    es.addEventListener('progress', e => {
      const d = JSON.parse(e.data)
      progress.value = `已索引 ${d.count} 个文件（${d.current}）`
    })
    es.addEventListener('suspected', e => { progress.value += `，疑似重复组 ${e.data} 个` })
    es.addEventListener('confirmed', e => { progress.value += `，已确认 ${e.data}` })
    es.addEventListener('phase', e => {
      if (e.data === 'DONE' || e.data === 'CANCELLED' || e.data === 'FAILED') {
        es.close()
        progress.value = e.data === 'DONE' ? '扫描完成' : `扫描结束: ${e.data}`
        refreshScans()
      }
    })
    es.onerror = () => { es.close(); refreshScans() }
  }).catch(e => ElMessage.error(errText(e)))
}

async function selectScan(id: number) {
  selectedScanId.value = id
  const { data } = await api.duplicates(id, 'CONFIRMED')
  groups.value = data
  const defaults: Record<number, number> = {}
  for (const g of data) {
    const newest = [...g.members].sort((a, b) => b.mtime.localeCompare(a.mtime))[0]
    if (newest) defaults[g.id] = newest.id
  }
  keepChoice.value = defaults
}

/** 一键清理：本次扫描全部确认重复组合并为一个计划，一次执行、可整体撤销。 */
async function cleanAll() {
  if (!selectedScanId.value || groups.value.length === 0) {
    ElMessage.info('当前扫描没有可清理的确认重复组')
    return
  }
  const total = groups.value.reduce((n, g) => n + g.members.length - 1, 0)
  try {
    await ElMessageBox.confirm(
      `将为 ${groups.value.length} 个重复组生成一个清理计划：保留每组最新副本，其余 ${total} 个文件移入安全区（可整体撤销）。确认？`,
      '一键清理全部重复组', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data: plan } = await api.createPlan({
      kind: 'DUPLICATE_CLEANUP_ALL', refId: selectedScanId.value, keepStrategy: 'NEWEST'
    })
    const { data: executed } = await api.execute(plan.id)
    ElMessage.success(`已清理 ${total} 个重复副本（计划 #${executed.id}），可到执行中心撤销`)
    await selectScan(selectedScanId.value)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function previewGroup(g: DuplicateGroup) {
  const keepId = keepChoice.value[g.id]
  const kept = g.members.find(m => m.id === keepId)
  const keepStrategy = 'NEWEST'
  const { data } = await api.dryRun({ kind: 'DUPLICATE_CLEANUP', refId: g.id, keepStrategy })
  preview.value = { ...data, description: `${data.description} —— 保留: ${kept?.path ?? '(最新)'}` }
  previewGroupId.value = g.id
  previewVisible.value = true
}

async function createPlanFromPreview() {
  if (!preview.value || !previewGroupId.value) return
  const { data: created } = await api.createPlan({
    kind: 'DUPLICATE_CLEANUP',
    refId: previewGroupId.value,
    keepStrategy: 'NEWEST'
  })
  previewVisible.value = false
  ElMessage.success(`计划 #${created.id} 已创建，请到「执行中心」执行`)
}

async function confirmCreateAndExecute(g: DuplicateGroup) {
  const keepId = keepChoice.value[g.id]
  if (!keepId) {
    ElMessage.warning('请先选择组内要保留的文件')
    return
  }
  const kept = g.members.find(m => m.id === keepId)
  try {
    if (kept) {
      await ElMessageBox.confirm(
        `将保留：${kept.path}\n其余 ${g.members.length - 1} 个副本移入安全区（可撤销）。确认？`,
        '创建并执行清理计划', { type: 'warning' })
    }
    const keepStrategy: CreatePlanRequest['keepStrategy'] = 'NEWEST'
    const { data: plan } = await api.createPlan({ kind: 'DUPLICATE_CLEANUP', refId: g.id, keepStrategy })
    const { data: executed } = await api.execute(plan.id)
    ElMessage.success(`计划 #${executed.id} 已执行：${executed.status}`)
    if (selectedScanId.value) await selectScan(selectedScanId.value)
  } catch (e: unknown) {
    // 用户在确认框点取消时 errText 为 'cancel'，不打扰
    if (errText(e) !== 'cancel') ElMessage.error(errText(e))
  }
}

refreshScans()
</script>

<template>
  <div>
    <el-form inline>
      <el-form-item label="扫描目录">
        <el-input v-model="rootPath" placeholder="/Users/you/Downloads 或 ~/Downloads" style="width: 340px"
                  @keyup.enter="startScan" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="startScan">开始扫描</el-button>
        <el-button @click="refreshScans">刷新</el-button>
      </el-form-item>
      <el-form-item>
        <el-button v-for="d in quickDirs" :key="d" size="small" @click="rootPath = d; startScan()">
          {{ d.split('/').pop() }}
        </el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="progress" :title="progress" type="info" :closable="false" style="margin-bottom: 12px" />

    <el-table :data="scans" size="small" style="margin-bottom: 16px"
              highlight-current-row @current-change="(row: ScanRow) => row && selectScan(row.id)">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column prop="rootPath" label="根目录" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'primary' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="统计" width="200">
        <template #default="{ row }">
          <span v-if="row.statsJson && row.status === 'DONE'">
            {{ JSON.parse(row.statsJson).files }} 文件 / 可释放
            {{ fmtSize(JSON.parse(row.statsJson).reclaimableBytes) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="rescan(row)">重新扫描</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="group-head">
      <h4>重复文件组（CONFIRMED，扫描 #{{ selectedScanId ?? '—' }}）</h4>
      <el-button v-if="groups.length > 1" type="danger" size="small" @click="cleanAll">
        一键清理全部（{{ groups.reduce((n, g) => n + g.members.length - 1, 0) }} 个副本）
      </el-button>
    </div>
    <el-empty v-if="groups.length === 0" description="暂无确认的重复组" />
    <div v-for="g in groups" :key="g.id" class="group-card">
      <div class="group-head">
        <span>组 #{{ g.id }} · {{ g.members.length }} 个副本 · 共 {{ fmtSize(g.totalSize) }}</span>
        <span>
          <el-button size="small" @click="previewGroup(g)">预览计划</el-button>
          <el-button size="small" type="danger" @click="confirmCreateAndExecute(g)">创建并执行</el-button>
        </span>
      </div>
      <el-radio-group v-model="keepChoice[g.id]">
        <div v-for="m in g.members" :key="m.id" class="member-row">
          <el-radio :value="m.id">{{ m.path }}（{{ fmtSize(m.size) }}，{{ fmtTime(m.mtime) }}）</el-radio>
        </div>
      </el-radio-group>
    </div>

    <el-dialog v-model="previewVisible" title="计划预览（dry-run，不落盘）" width="720px">
      <p>{{ preview?.description }}</p>
      <el-table :data="preview?.operations ?? []" size="small" max-height="360">
        <el-table-column prop="type" label="操作" width="170" />
        <el-table-column prop="src" label="源" show-overflow-tooltip />
        <el-table-column prop="dst" label="目标" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="error" label="预检信息" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" @click="createPlanFromPreview">创建计划</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.group-card { border: 1px solid var(--el-border-color-light); border-radius: 6px; padding: 10px 14px; margin-bottom: 10px; }
.group-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.group-head h4 { margin: 0; }
.member-row { display: block; }
</style>
