<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import { fmtTime } from '../utils'
import type { Plan, PlanRow } from '../types'

const plans = ref<PlanRow[]>([])
const detail = ref<Plan | null>(null)
const detailVisible = ref(false)

const statusType = (s: string) =>
  s === 'EXECUTED' ? 'success'
    : s === 'FAILED' || s === 'NEEDS_REVIEW' ? 'danger'
      : s === 'ROLLED_BACK' ? 'warning'
        : s === 'ACKNOWLEDGED' ? 'info'
          : s === 'EXECUTING' ? 'primary' : 'info'

async function refresh() {
  try {
    plans.value = (await api.plans()).data
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function openDetail(row: PlanRow) {
  try {
    detail.value = (await api.plan(row.id)).data
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function execute(row: PlanRow) {
  try {
    await ElMessageBox.confirm(`执行计划 #${row.id}？执行中失败会自动回滚。`, '确认执行', { type: 'warning' })
  } catch {
    return // 用户点取消：confirm 的 reject 不是错误，静默返回即可
  }
  try {
    const { data } = await api.execute(row.id)
    ElMessage.success(`执行完成: ${data.status}`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

async function rollback(row: PlanRow) {
  try {
    await ElMessageBox.confirm(`撤销计划 #${row.id}？文件将恢复到执行前状态。`, '确认撤销', { type: 'warning' })
  } catch {
    return // 用户点取消
  }
  try {
    const { data } = await api.rollback(row.id)
    ElMessage.success(`撤销完成: ${data.status}`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

/** NEEDS_REVIEW 的人工闭环：核对完矛盾条目后标记，转终态 ACKNOWLEDGED。 */
async function ackReview(row: PlanRow) {
  try {
    await ElMessageBox.confirm(
      '标记「已人工复核」表示你已核对详情中的矛盾条目并妥善处理，此后该计划不再提醒。确认？',
      '标记已复核', { type: 'warning' })
  } catch {
    return // 用户点取消
  }
  try {
    await api.ackReview(row.id)
    ElMessage.success(`计划 #${row.id} 已标记复核完成`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

refresh()

defineExpose({ refresh })
</script>

<template>
  <div>
    <el-button type="primary" @click="refresh">刷新</el-button>
    <el-table :data="plans" style="margin-top: 12px" size="small">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="error" label="错误" show-overflow-tooltip />
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'PLANNED'" size="small" type="primary" @click="execute(row)">
            执行
          </el-button>
          <el-button v-if="row.status === 'EXECUTED'" size="small" type="warning" @click="rollback(row)">
            撤销
          </el-button>
          <el-button v-if="row.status === 'NEEDS_REVIEW'" size="small" type="danger" @click="ackReview(row)">
            标记已复核
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="plans.some(p => p.status === 'NEEDS_REVIEW')"
              title="存在 NEEDS_REVIEW 计划：崩溃恢复时发现与磁盘状态矛盾的条目，请打开详情人工核对，处理后点「标记已复核」闭环"
              type="error" :closable="false" style="margin-top: 12px" />

    <el-dialog v-model="detailVisible" :title="`计划 #${detail?.id}`" width="min(820px, 94vw)">
      <p>{{ detail?.description }}</p>
      <el-alert v-if="detail?.error" :title="detail?.error" type="error" :closable="false" />
      <el-table :data="detail?.operations ?? []" size="small" max-height="420">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column prop="type" label="操作" width="170" />
        <el-table-column prop="src" label="源" show-overflow-tooltip />
        <el-table-column prop="dst" label="目标" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="error" label="信息" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>
