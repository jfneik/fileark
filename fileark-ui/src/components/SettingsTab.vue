<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'

const rows = ref<{ key: string; value: string }[]>([])
const newKey = ref('')
const newValue = ref('')

async function refresh() {
  try {
    const all = (await api.settings()).data
    rows.value = Object.entries(all).map(([key, value]) => ({ key, value }))
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function save() {
  const kv: Record<string, string> = {}
  rows.value.forEach(r => { kv[r.key] = r.value })
  try {
    await api.saveSettings(kv)
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

function add() {
  if (!newKey.value) return
  rows.value.push({ key: newKey.value, value: newValue.value })
  newKey.value = ''
  newValue.value = ''
}

/** 真删除：调用后端 DELETE /settings/{key}，刷新后不会复活。 */
async function removeRow(row: { key: string; value: string }) {
  try {
    await ElMessageBox.confirm(`删除设置项「${row.key}」？运行时将回退到 application.yml 的默认值。`, '删除设置', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteSetting(row.key)
    ElMessage.success('已删除')
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
    <p class="hint">
      常用键：scan.excludes（逗号分隔的排除目录名）、similar.threshold（相似图汉明距离阈值）等。
      运行时行为以 application.yml 为默认值，此处为覆盖项。
    </p>
    <el-table :data="rows" size="small">
      <el-table-column prop="key" label="键" width="260" />
      <el-table-column label="值">
        <template #default="{ row }">
          <el-input v-model="row.value" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" type="danger" text @click="removeRow(row)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div style="margin-top: 12px">
      <el-input v-model="newKey" placeholder="新键" style="width: 240px" />
      <el-input v-model="newValue" placeholder="值" style="width: 320px" />
      <el-button @click="add">添加</el-button>
      <el-button type="primary" @click="save">保存全部</el-button>
    </div>
  </div>
</template>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 13px; margin-top: 0; }
</style>
