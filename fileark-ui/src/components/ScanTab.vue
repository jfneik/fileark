<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import { fmtSize, fmtTime, pickKept, KEEP_STRATEGY_LABELS, type KeepStrategy } from '../utils'
import type { DuplicateGroup, Plan, ScanRow } from '../types'

const rootPath = ref('')
const scans = ref<ScanRow[]>([])
const selectedScanId = ref<number>()
const groups = ref<DuplicateGroup[]>([])
const progress = ref('')
const keepStrategy = ref<KeepStrategy>('NEWEST')
const preview = ref<Plan | null>(null)
const previewGroupId = ref<number>()
const previewVisible = ref(false)
const quickDirs = ['~/Downloads', '~/Desktop', '~/Documents']

let es: EventSource | null = null

async function refreshScans() {
  try {
    scans.value = (await api.listScans()).data
  } catch (e) {
    ElMessage.error(errText(e))
    return
  }
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

async function cancelScan(row: ScanRow) {
  try {
    await ElMessageBox.confirm(
      `取消正在进行的扫描 #${row.id}（${row.rootPath}）？已索引的文件会保留。`, '取消扫描', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.cancelScan(row.id)
    ElMessage.success(`已请求取消扫描 #${row.id}`)
    await refreshScans()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function launchScan(dir: string) {
  api.createScan(dir).then(({ data }) => {
    ElMessage.success(`扫描 #${data.scanId} 已启动`)
    es?.close() // 只保留最新一次扫描的实时进度
    const source = new EventSource(`/api/scans/${data.scanId}/events`)
    es = source
    source.addEventListener('progress', e => {
      const d = JSON.parse(e.data)
      progress.value = `已索引 ${d.count} 个文件（${d.current}）`
    })
    source.addEventListener('suspected', e => { progress.value += `，疑似重复组 ${e.data} 个` })
    source.addEventListener('confirmed', e => { progress.value += `，已确认 ${e.data}` })
    source.addEventListener('phase', e => {
      if (e.data === 'DONE' || e.data === 'CANCELLED' || e.data === 'FAILED') {
        source.close()
        if (es === source) es = null
        progress.value = e.data === 'DONE' ? '扫描完成' : `扫描结束: ${e.data}`
        refreshScans()
      }
    })
    // 网络抖动时交给 EventSource 原生重连；浏览器彻底放弃（readyState=CLOSED）才收尾
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        if (es === source) es = null
        refreshScans()
      }
    }
  }).catch(e => ElMessage.error(errText(e)))
}

onUnmounted(() => { es?.close(); es = null })

let selectSeq = 0

async function selectScan(id: number) {
  selectedScanId.value = id
  const seq = ++selectSeq
  try {
    const { data } = await api.duplicates(id, 'CONFIRMED')
    if (seq === selectSeq) groups.value = data
  } catch (e) {
    if (seq === selectSeq) {
      groups.value = []
      ElMessage.error(errText(e))
    }
  }
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
      `将为 ${groups.value.length} 个重复组生成一个清理计划：按「${KEEP_STRATEGY_LABELS[keepStrategy.value]}」，` +
      `其余 ${total} 个文件移入安全区（可整体撤销）。确认？`,
      '一键清理全部重复组', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data: plan } = await api.createPlan({
      kind: 'DUPLICATE_CLEANUP_ALL', refId: selectedScanId.value, keepStrategy: keepStrategy.value
    })
    const { data: executed } = await api.execute(plan.id)
    ElMessage.success(`已清理 ${total} 个重复副本（计划 #${executed.id}），可到执行中心撤销`)
    await selectScan(selectedScanId.value)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function previewGroup(g: DuplicateGroup) {
  try {
    const { data } = await api.dryRun({ kind: 'DUPLICATE_CLEANUP', refId: g.id, keepStrategy: keepStrategy.value })
    const kept = pickKept(g.members, keepStrategy.value)
    preview.value = { ...data, description: `${data.description} —— 将保留: ${kept?.path ?? '(空组)'}` }
    previewGroupId.value = g.id
    previewVisible.value = true
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function createPlanFromPreview() {
  if (!preview.value || !previewGroupId.value) return
  try {
    const { data: created } = await api.createPlan({
      kind: 'DUPLICATE_CLEANUP',
      refId: previewGroupId.value,
      keepStrategy: keepStrategy.value
    })
    previewVisible.value = false
    ElMessage.success(`计划 #${created.id} 已创建，请到「执行中心」执行`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function confirmCreateAndExecute(g: DuplicateGroup) {
  const kept = pickKept(g.members, keepStrategy.value)
  if (!kept) {
    ElMessage.warning('该组没有可保留的副本')
    return
  }
  try {
    await ElMessageBox.confirm(
      `按「${KEEP_STRATEGY_LABELS[keepStrategy.value]}」将保留：${kept.path}\n` +
      `其余 ${g.members.length - 1} 个副本移入安全区（可撤销）。确认？`,
      '创建并执行清理计划', { type: 'warning' })
  } catch {
    return // 用户点取消
  }
  try {
    const { data: plan } = await api.createPlan({ kind: 'DUPLICATE_CLEANUP', refId: g.id, keepStrategy: keepStrategy.value })
    const { data: executed } = await api.execute(plan.id)
    ElMessage.success(`计划 #${executed.id} 已执行：${executed.status}`)
    if (selectedScanId.value) await selectScan(selectedScanId.value)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

/** 导出重复报告 CSV（含全部组与状态列），用于留档或人工核对。 */
function exportCsv() {
  window.open(`/api/scans/${selectedScanId.value}/duplicates/export`)
}

refreshScans()

defineExpose({ refresh: refreshScans })
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
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="rescan(row)">重新扫描</el-button>
          <el-button v-if="row.status === 'RUNNING'" size="small" text type="danger" @click="cancelScan(row)">
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="group-head">
      <h4>重复文件组（CONFIRMED，扫描 #{{ selectedScanId ?? '—' }}）</h4>
      <span class="group-tools">
        <template v-if="groups.length > 0">
          <span class="keep-label">保留策略</span>
          <el-select v-model="keepStrategy" size="small" style="width: 160px">
            <el-option v-for="(label, s) in KEEP_STRATEGY_LABELS" :key="s" :value="s" :label="label" />
          </el-select>
        </template>
        <el-button v-if="selectedScanId" size="small" @click="exportCsv">导出 CSV 报告</el-button>
        <el-button v-if="groups.length > 1" type="danger" size="small" @click="cleanAll">
          一键清理全部（{{ groups.reduce((n, g) => n + g.members.length - 1, 0) }} 个副本）
        </el-button>
      </span>
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
      <p class="kept-line">将保留：{{ pickKept(g.members, keepStrategy)?.path }}</p>
      <div v-for="m in g.members" :key="m.id" class="member-row">
        {{ m.path }}（{{ fmtSize(m.size) }}，{{ fmtTime(m.mtime) }}）
      </div>
    </div>

    <el-dialog v-model="previewVisible" title="计划预览（dry-run，不落盘）" width="min(720px, 94vw)">
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
.group-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; gap: 8px; flex-wrap: wrap; }
.group-head h4 { margin: 0; }
.group-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.keep-label { font-size: 12px; color: var(--el-text-color-secondary); }
.kept-line { margin: 0 0 6px; font-size: 13px; color: var(--el-color-success); word-break: break-all; }
.member-row { display: block; font-size: 13px; line-height: 1.8; word-break: break-all; }
</style>
