<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import type { Plan, PlanRow } from '../types'

const plans = ref<PlanRow[]>([])
const detail = ref<Plan | null>(null)
const detailVisible = ref(false)

const statusType = (s: string) =>
  s === 'EXECUTED' ? 'success'
    : s === 'FAILED' || s === 'NEEDS_REVIEW' ? 'danger'
      : s === 'ROLLED_BACK' ? 'warning'
        : s === 'EXECUTING' ? 'primary' : 'info'

async function refresh() {
  plans.value = (await api.plans()).data
}

async function openDetail(row: PlanRow) {
  detail.value = (await api.plan(row.id)).data
  detailVisible.value = true
}

async function execute(row: PlanRow) {
  await ElMessageBox.confirm(`执行计划 #${row.id}？执行中失败会自动回滚。`, '确认执行', { type: 'warning' })
  try {
    const { data } = await api.execute(row.id)
    ElMessage.success(`执行完成: ${data.status}`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

async function rollback(row: PlanRow) {
  await ElMessageBox.confirm(`撤销计划 #${row.id}？文件将恢复到执行前状态。`, '确认撤销', { type: 'warning' })
  try {
    const { data } = await api.rollback(row.id)
    ElMessage.success(`撤销完成: ${data.status}`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

refresh()
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
      <el-table-column prop="createdAt" label="创建时间" width="180" show-overflow-tooltip />
      <el-table-column prop="error" label="错误" show-overflow-tooltip />
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'PLANNED'" size="small" type="primary" @click="execute(row)">
            执行
          </el-button>
          <el-button v-if="row.status === 'EXECUTED'" size="small" type="warning" @click="rollback(row)">
            撤销
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="plans.some(p => p.status === 'NEEDS_REVIEW')"
              title="存在 NEEDS_REVIEW 计划：崩溃恢复时发现与磁盘状态矛盾的条目，请打开详情人工核对"
              type="error" :closable="false" style="margin-top: 12px" />

    <el-dialog v-model="detailVisible" :title="`计划 #${detail?.id}`" width="820px">
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
