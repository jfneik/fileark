<script setup lang="ts">
import { ref } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import DashboardTab from './components/DashboardTab.vue'
import ScanTab from './components/ScanTab.vue'
import SimilarTab from './components/SimilarTab.vue'
import RulesTab from './components/RulesTab.vue'
import PlansTab from './components/PlansTab.vue'
import QuarantineTab from './components/QuarantineTab.vue'
import WatchTab from './components/WatchTab.vue'
import SettingsTab from './components/SettingsTab.vue'

const activeTab = ref('dashboard')

// 各 Tab 暴露 refresh()，切换到对应 Tab 时自动刷新，避免看到陈旧数据
const tabRefs: Record<string, ComponentPublicInstance | null> = {}
const setRef = (name: string) => (el: ComponentPublicInstance | null) => { tabRefs[name] = el }
function onTabChange(name: string | number) {
  (tabRefs[String(name)] as { refresh?: () => void } | null | undefined)?.refresh?.()
}
</script>

<template>
  <el-container>
    <el-header height="auto" class="app-header">
      <div class="header-inner">
        <div class="brand">
          <svg class="brand-mark" viewBox="0 0 36 36" aria-hidden="true">
            <defs>
              <linearGradient id="ark-logo-g" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#6c8cff" />
                <stop offset="1" stop-color="#3b5bdb" />
              </linearGradient>
            </defs>
            <rect x="1" y="1" width="34" height="34" rx="10" fill="url(#ark-logo-g)" />
            <path
              d="M9 14.5 18 10l9 4.5v7L18 26l-9-4.5z"
              fill="none"
              stroke="rgba(255,255,255,.95)"
              stroke-width="2"
              stroke-linejoin="round"
            />
            <path d="M9 14.5 18 19l9-4.5M18 19v7" fill="none" stroke="rgba(255,255,255,.6)" stroke-width="2" stroke-linejoin="round" />
          </svg>
          <div class="brand-text">
            <h1>FileArk <em>文件方舟</em></h1>
            <p>扫描查重 · 规则整理 · 事务式执行</p>
          </div>
        </div>
        <span class="header-chip">🛡️ 所有删除先进安全区，所有操作可撤销</span>
      </div>
    </el-header>
    <el-main style="padding-top: 16px">
      <el-tabs v-model="activeTab" class="app-tabs" @tab-change="onTabChange">
        <el-tab-pane lazy label="仪表盘" name="dashboard"><DashboardTab :ref="setRef('dashboard')" /></el-tab-pane>
        <el-tab-pane lazy label="扫描查重" name="scan"><ScanTab :ref="setRef('scan')" /></el-tab-pane>
        <el-tab-pane lazy label="相似图片" name="similar"><SimilarTab :ref="setRef('similar')" /></el-tab-pane>
        <el-tab-pane lazy label="规则管理" name="rules"><RulesTab :ref="setRef('rules')" /></el-tab-pane>
        <el-tab-pane lazy label="执行中心" name="plans"><PlansTab :ref="setRef('plans')" /></el-tab-pane>
        <el-tab-pane lazy label="安全区" name="quarantine"><QuarantineTab :ref="setRef('quarantine')" /></el-tab-pane>
        <el-tab-pane lazy label="目录监控" name="watch"><WatchTab :ref="setRef('watch')" /></el-tab-pane>
        <el-tab-pane lazy label="设置" name="settings"><SettingsTab :ref="setRef('settings')" /></el-tab-pane>
      </el-tabs>
    </el-main>
  </el-container>
</template>
