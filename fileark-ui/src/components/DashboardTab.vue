<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errText } from '../api'
import { fmtSize, CATEGORY_LABELS } from '../utils'
import type { Dashboard } from '../types'

const data = ref<Dashboard | null>(null)
const loading = ref(false)
const failed = ref(false)

async function refresh() {
  loading.value = true
  failed.value = false
  try {
    data.value = (await api.dashboard()).data
  } catch (e) {
    failed.value = true
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

refresh()

defineExpose({ refresh })
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="failed" type="error" :closable="false" style="margin-bottom: 12px">
      <template #title>
        仪表盘加载失败，请确认后端服务已启动
        <el-button size="small" style="margin-left: 8px" @click="refresh">重试</el-button>
      </template>
    </el-alert>
    <el-button type="primary" @click="refresh">刷新</el-button>
    <el-row v-if="!failed" :gutter="12" style="margin-top: 12px">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-head"><span class="stat-icon blue">🗂️</span>已索引文件</div>
          <el-statistic :value="data?.indexedFiles ?? 0" class="stat-num" />
          <p class="sub">{{ fmtSize(data?.indexedBytes ?? 0) }} · 共 {{ data?.totalScans ?? 0 }} 次扫描</p>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-head"><span class="stat-icon rose">🔁</span>确认重复组</div>
          <el-statistic :value="data?.duplicateGroups ?? 0" class="stat-num" />
          <p class="sub">可释放 {{ fmtSize(data?.reclaimableBytes ?? 0) }}</p>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-head"><span class="stat-icon green">🛡️</span>安全区文件</div>
          <el-statistic :value="data?.quarantine?.items ?? 0" class="stat-num" />
          <p class="sub">
            {{ fmtSize(data?.quarantine?.bytes ?? 0) }}
            <el-tag v-if="(data?.quarantine?.expired ?? 0) > 0" type="danger" size="small">
              {{ data?.quarantine?.expired }} 个已到期
            </el-tag>
          </p>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-head"><span class="stat-icon violet">🧾</span>计划总数</div>
          <el-statistic
            :value="Object.values(data?.plans ?? {}).reduce((a, b) => a + b, 0)"
            class="stat-num"
          />
          <p class="sub">
            <span v-for="(n, s) in data?.plans ?? {}" :key="s" class="plan-stat">{{ s }}: {{ n }}</span>
          </p>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="data?.latestScan" shadow="never" style="margin-top: 12px">
      <template #header>最近扫描 #{{ data.latestScan.id }}：{{ data.latestScan.rootPath }}</template>
      <div v-for="(n, c) in data.categories" :key="c" class="cat-row">
        <span class="cat-name">{{ CATEGORY_LABELS[String(c)] ?? c }}</span>
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
