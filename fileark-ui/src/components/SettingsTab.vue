<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errText } from '../api'

const rows = ref<{ key: string; value: string }[]>([])
const newKey = ref('')
const newValue = ref('')

async function refresh() {
  const all = (await api.settings()).data
  rows.value = Object.entries(all).map(([key, value]) => ({ key, value }))
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

refresh()
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
        <template #default="{ $index }">
          <el-button size="small" type="danger" text @click="rows.splice($index, 1)">删</el-button>
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
