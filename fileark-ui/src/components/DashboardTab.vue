<script setup lang="ts">
import { ref } from 'vue'
import { api, errText } from '../api'
import type { Dashboard } from '../types'

const data = ref<Dashboard | null>(null)
const loading = ref(false)

function fmtSize(bytes: number): string {
  if (bytes > 1 << 30) return (bytes / (1 << 30)).toFixed(2) + ' GB'
  if (bytes > 1 << 20) return (bytes / (1 << 20)).toFixed(2) + ' MB'
  if (bytes > 1 << 10) return (bytes / (1 << 10)).toFixed(1) + ' KB'
  return bytes + ' B'
}

async function refresh() {
  loading.value = true
  try {
    data.value = (await api.dashboard()).data
  } catch (e) {
    console.error(errText(e))
  } finally {
    loading.value = false
  }
}

const categoryNames: Record<string, string> = {
  IMAGE: '图片', VIDEO: '视频', AUDIO: '音频', DOCUMENT: '文档',
  ARCHIVE: '压缩包', CODE: '代码', OTHER: '其他'
}

refresh()
</script>

<template>
  <div v-loading="loading">
    <el-button type="primary" @click="refresh">刷新</el-button>
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="6">
        <el-card shadow="never">
          <el-statistic title="已索引文件" :value="data?.indexedFiles ?? 0" />
          <p class="sub">{{ fmtSize(data?.indexedBytes ?? 0) }} · 共 {{ data?.totalScans ?? 0 }} 次扫描</p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <el-statistic title="确认重复组" :value="data?.duplicateGroups ?? 0" />
          <p class="sub">可释放 {{ fmtSize(data?.reclaimableBytes ?? 0) }}</p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <el-statistic title="安全区文件" :value="data?.quarantine?.items ?? 0" />
          <p class="sub">
            {{ fmtSize(data?.quarantine?.bytes ?? 0) }}
            <el-tag v-if="(data?.quarantine?.expired ?? 0) > 0" type="danger" size="small">
              {{ data?.quarantine?.expired }} 个已到期
            </el-tag>
          </p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <el-statistic title="计划总数"
                        :value="Object.values(data?.plans ?? {}).reduce((a, b) => a + b, 0)" />
          <p class="sub">
            <span v-for="(n, s) in data?.plans ?? {}" :key="s" class="plan-stat">{{ s }}: {{ n }}</span>
          </p>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="data?.latestScan" shadow="never" style="margin-top: 12px">
      <template #header>最近扫描 #{{ data.latestScan.id }}：{{ data.latestScan.rootPath }}</template>
      <div v-for="(n, c) in data.categories" :key="c" class="cat-row">
        <span class="cat-name">{{ categoryNames[String(c)] ?? c }}</span>
        <el-progress :percentage="Math.round((Number(n) / Math.max(1, data.indexedFiles)) * 100)"
                     :format="() => `${n}`" />
      </div>
    </el-card>
    <el-empty v-else description="尚无完成的扫描，去「扫描查重」发起一次" style="margin-top: 24px" />
  </div>
</template>

<style scoped>
.sub { color: var(--el-text-color-secondary); font-size: 12px; margin: 4px 0 0; }
.plan-stat { margin-right: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.cat-row { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.cat-name { width: 56px; font-size: 13px; }
.cat-row :deep(.el-progress) { flex: 1; }
</style>
