<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import type { WatchStatus } from '../types'

const dirs = ref<WatchStatus[]>([])
const rulesEnabled = ref(false)
const newDir = ref('')

async function refresh() {
  const { data } = await api.watchStatus()
  dirs.value = data.active
  rulesEnabled.value = data.rulesEnabled
}

async function start() {
  if (!newDir.value) {
    ElMessage.warning('请输入要监控的目录')
    return
  }
  try {
    await api.startWatch(newDir.value)
    ElMessage.success('监控已启动，新文件稳定后将按规则自动整理')
    newDir.value = ''
    await refresh()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function stop(item: WatchStatus) {
  await ElMessageBox.confirm(`停止监控 ${item.dir}？已产生的操作不受影响。`, '停止监控')
  try {
    await api.stopWatch(item.dir)
    await refresh()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

refresh()
</script>

<template>
  <div>
    <el-alert v-if="!rulesEnabled"
              title="当前没有任何启用的规则：监控不会产生动作。请先到「规则管理」创建并启用规则。"
              type="warning" :closable="false" style="margin-bottom: 12px" />
    <el-form inline>
      <el-form-item label="监控目录">
        <el-input v-model="newDir" placeholder="/Users/you/Downloads" style="width: 360px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="start">启动监控</el-button>
        <el-button @click="refresh">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="dirs" size="small">
      <el-table-column prop="dir" label="目录" show-overflow-tooltip />
      <el-table-column label="状态" width="120">
        <template #default>
          <el-tag type="success">监控中</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="danger" @click="stop(row)">停止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="dirs.length === 0" description="尚未监控任何目录" />
    <p class="hint">
      监控原理：WatchService 监听创建/修改事件 → 500ms 防抖 → 两次采样确认文件稳定 →
      规则引擎求值 → 生成自动计划并事务执行（可在「执行中心」撤销）。
    </p>
  </div>
</template>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
