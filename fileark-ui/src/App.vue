<script setup lang="ts">
import ScanTab from './components/ScanTab.vue'
import RulesTab from './components/RulesTab.vue'
import PlansTab from './components/PlansTab.vue'
import QuarantineTab from './components/QuarantineTab.vue'
import SettingsTab from './components/SettingsTab.vue'
</script>

<template>
  <el-container>
    <el-header height="auto" style="padding: 14px 24px 0">
      <h2 style="margin: 0 0 4px">📦 FileArk 文件方舟</h2>
      <p style="margin: 0 0 8px; color: var(--el-text-color-secondary)">
        本地文件智能整理与去重引擎 —— 所有删除先进安全区，所有操作可撤销
      </p>
    </el-header>
    <el-main style="padding-top: 0">
      <el-tabs>
        <el-tab-pane lazy label="扫描查重"><ScanTab /></el-tab-pane>
        <el-tab-pane lazy label="规则管理"><RulesTab /></el-tab-pane>
        <el-tab-pane lazy label="执行中心"><PlansTab /></el-tab-pane>
        <el-tab-pane lazy label="安全区"><QuarantineTab /></el-tab-pane>
        <el-tab-pane lazy label="设置"><SettingsTab /></el-tab-pane>
      </el-tabs>
    </el-main>
  </el-container>
</template>

<style>
body { font-family: 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif; margin: 0; }
</style>
