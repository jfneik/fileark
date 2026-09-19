<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import type { QuarantineItem } from '../types'

const items = ref<QuarantineItem[]>([])

function daysLeft(item: QuarantineItem): number {
  return Math.max(0, Math.ceil((new Date(item.purgeAfter).getTime() - Date.now()) / 86400000))
}

function fmtSize(bytes: number): string {
  if (bytes > 1 << 20) return (bytes / (1 << 20)).toFixed(2) + ' MB'
  if (bytes > 1 << 10) return (bytes / (1 << 10)).toFixed(1) + ' KB'
  return bytes + ' B'
}

async function refresh() {
  items.value = (await api.quarantine()).data
}

async function restore(item: QuarantineItem) {
  await ElMessageBox.confirm(`恢复到原路径：${item.originalPath}？`, '恢复', { type: 'info' })
  try {
    await api.restore(item.id)
    ElMessage.success('已恢复')
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

async function purge(item: QuarantineItem) {
  await ElMessageBox.confirm(`彻底删除「${item.originalPath}」？此操作不可撤销！`, '危险操作', { type: 'error' })
  await ElMessageBox.confirm('再次确认：文件将从磁盘永久删除。', '二次确认', { type: 'error' })
  try {
    await api.purge(item.id)
    ElMessage.success('已彻底删除')
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
    <el-table :data="items" style="margin-top: 12px" size="small">
      <el-table-column prop="originalPath" label="原始路径" show-overflow-tooltip />
      <el-table-column label="大小" width="110">
        <template #default="{ row }">{{ fmtSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="剩余保留" width="110">
        <template #default="{ row }">
          <el-tag :type="daysLeft(row) <= 3 ? 'danger' : 'info'">{{ daysLeft(row) }} 天</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="quarantinedAt" label="入区时间" width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="restore(row)">恢复</el-button>
          <el-button size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
