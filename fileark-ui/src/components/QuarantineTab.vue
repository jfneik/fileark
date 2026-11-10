<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import { fmtSize, fmtTime } from '../utils'
import type { QuarantineItem } from '../types'

const items = ref<QuarantineItem[]>([])

function daysLeft(item: QuarantineItem): number {
  return Math.max(0, Math.ceil((new Date(item.purgeAfter).getTime() - Date.now()) / 86400000))
}

const expiredCount = computed(() => items.value.filter(i => daysLeft(i) <= 0).length)

async function refresh() {
  try {
    items.value = (await api.quarantine()).data
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function restore(item: QuarantineItem) {
  try {
    await ElMessageBox.confirm(`恢复到原路径：${item.originalPath}？`, '恢复', { type: 'info' })
  } catch {
    return
  }
  try {
    await api.restore(item.id)
    ElMessage.success('已恢复')
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

async function purge(item: QuarantineItem) {
  try {
    await ElMessageBox.confirm(`彻底删除「${item.originalPath}」？此操作不可撤销！`, '危险操作', { type: 'error' })
    await ElMessageBox.confirm('再次确认：文件将从磁盘永久删除。', '二次确认', { type: 'error' })
  } catch {
    return
  }
  try {
    await api.purge(item.id)
    ElMessage.success('已彻底删除')
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

/** 一键清除全部已到期条目（保留期 30 天，到期文件占着空间没有恢复价值时清理）。 */
async function purgeExpired() {
  try {
    await ElMessageBox.confirm(
      `将物理删除全部 ${expiredCount.value} 个已到期文件，不可恢复。确认？`,
      '清除已到期', { type: 'error' })
  } catch {
    return
  }
  try {
    const { data } = await api.purgeExpired()
    ElMessage.success(`已清除 ${data.purged} 个到期文件`)
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
    <el-button v-if="expiredCount > 0" type="danger" @click="purgeExpired">
      清除已到期（{{ expiredCount }}）
    </el-button>
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
      <el-table-column label="入区时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.quarantinedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="restore(row)">恢复</el-button>
          <el-button size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
