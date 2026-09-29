<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errText } from '../api'
import { fmtSize } from '../utils'
import type { FileEntry, ScanRow } from '../types'

const scans = ref<ScanRow[]>([])
const scanId = ref<number>()
const threshold = ref(10)
const groups = ref<FileEntry[][]>([])
const loading = ref(false)

async function refreshScans() {
  scans.value = (await api.listScans()).data.filter(s => s.status === 'DONE')
  if (scanId.value == null && scans.value.length > 0) {
    scanId.value = scans.value[0].id
  }
}

async function detect() {
  if (!scanId.value) {
    ElMessage.warning('请先完成一次扫描（相似图片检测基于扫描索引）')
    return
  }
  loading.value = true
  try {
    const { data } = await api.similar(scanId.value, threshold.value)
    groups.value = data
    if (data.length === 0) ElMessage.info('未发现相似图片（可尝试调大阈值，建议 8–15）')
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

refreshScans()
</script>

<template>
  <div v-loading="loading">
    <el-form inline>
      <el-form-item label="基于扫描">
        <el-select v-model="scanId" style="width: 300px" placeholder="选择一次已完成的扫描">
          <el-option v-for="s in scans" :key="s.id" :value="s.id"
                     :label="`#${s.id} ${s.rootPath}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="汉明距离阈值">
        <el-input-number v-model="threshold" :min="4" :max="24" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="detect">开始检测</el-button>
      </el-form-item>
    </el-form>

    <p class="hint">
      原理：对扫描索引中的图片计算 dHash 感知哈希（64-bit），两两汉明距离 ≤ 阈值即归为相似组（并查集传递闭包）。
      阈值越小越严格（越像才算相似）。
    </p>

    <el-empty v-if="groups.length === 0" description="点击「开始检测」查找相似图片" />
    <div v-for="(g, gi) in groups" :key="gi" class="group-card">
      <div class="group-head">相似组 {{ gi + 1 }} · {{ g.length }} 张</div>
      <div class="thumbs">
        <div v-for="m in g" :key="m.id" class="thumb-item">
          <el-image :src="api.thumbnailUrl(m.id)" fit="cover" class="thumb"
                    :preview-src-list="[api.thumbnailUrl(m.id)]" preview-teleported lazy>
            <template #error>
              <div class="thumb-fallback">无法预览</div>
            </template>
          </el-image>
          <p class="thumb-path" :title="m.path">{{ m.path }}</p>
          <p class="thumb-meta">{{ fmtSize(m.size) }} · {{ m.mtime.slice(0, 10) }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.group-card { border: 1px solid var(--el-border-color-light); border-radius: 6px; padding: 10px 14px; margin-bottom: 12px; }
.group-head { font-weight: 600; margin-bottom: 8px; }
.thumbs { display: flex; flex-wrap: wrap; gap: 12px; }
.thumb-item { width: 150px; }
.thumb { width: 150px; height: 110px; border-radius: 4px; background: var(--el-fill-color-light); }
.thumb-fallback { display: flex; align-items: center; justify-content: center; height: 110px; color: var(--el-text-color-secondary); font-size: 12px; }
.thumb-path { font-size: 12px; margin: 4px 0 0; word-break: break-all; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.thumb-meta { font-size: 12px; color: var(--el-text-color-secondary); margin: 2px 0 0; }
</style>
