<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import { CATEGORY_LABELS } from '../utils'
import type { Condition, Plan, Rule } from '../types'

const rules = ref<Rule[]>([])
const visible = ref(false)
const editing = ref<Rule | null>(null)
const saving = ref(false)
const applyVisible = ref(false)
const applyRule = ref<Rule | null>(null)
const targetDir = ref('')
const applying = ref(false)
const applyPreview = ref<Plan | null>(null)

const emptyRule = (): Rule => ({
  id: 0,
  name: '',
  priority: 5,
  enabled: true,
  conditions: [{ field: 'TYPE', op: 'EQ', value: 'IMAGE' }],
  action: { type: 'MOVE', targetTemplate: '{type}/{yyyy}/{MM}', tag: null }
})

const fields: Condition['field'][] = ['TYPE', 'SIZE_BYTES', 'AGE_DAYS', 'NAME_REGEX_FIELD', 'SRC_PREFIX']
const ops: Condition['op'][] = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'MATCHES']
/** 枚举值(存储与 API 协议)保持英文不变,界面一律显示中文标签。 */
const FIELD_LABELS: Record<Condition['field'], string> = {
  TYPE: '类型',
  SIZE_BYTES: '大小（字节）',
  AGE_DAYS: '文件龄（天）',
  NAME_REGEX_FIELD: '文件名（正则）',
  SRC_PREFIX: '路径前缀'
}
const OP_LABELS: Record<Condition['op'], string> = {
  EQ: '等于',
  NEQ: '不等于',
  GT: '大于',
  GTE: '大于等于',
  LT: '小于',
  LTE: '小于等于',
  MATCHES: '正则匹配'
}
const ACTION_LABELS: Record<Rule['action']['type'], string> = {
  MOVE: '移动',
  RENAME: '重命名',
  TAG: '标记'
}

const categories = Object.keys(CATEGORY_LABELS)
const VALUE_PLACEHOLDERS: Record<Condition['field'], string> = {
  TYPE: '从下拉选择类别',
  SIZE_BYTES: '字节数，如 102400',
  AGE_DAYS: '天数，如 30',
  NAME_REGEX_FIELD: '文件名正则，如 report.*\\.pdf',
  SRC_PREFIX: '路径前缀，如 D:/照片'
}

function condText(c: Condition): string {
  const value = c.field === 'TYPE' ? (CATEGORY_LABELS[c.value] ?? c.value) : c.value
  return `${FIELD_LABELS[c.field]} ${OP_LABELS[c.op]} ${value}`
}

async function refresh() {
  try {
    rules.value = (await api.rules()).data
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

function openCreate() {
  editing.value = emptyRule()
  visible.value = true
}

function openEdit(rule: Rule) {
  editing.value = JSON.parse(JSON.stringify(rule))
  visible.value = true
}

async function save() {
  if (!editing.value) return
  saving.value = true
  try {
    if (editing.value.id) {
      await api.updateRule(editing.value)
    } else {
      await api.createRule(editing.value)
    }
    visible.value = false
    ElMessage.success('已保存')
    await refresh()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    saving.value = false
  }
}

async function remove(rule: Rule) {
  try {
    await ElMessageBox.confirm(`删除规则「${rule.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteRule(rule.id)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await refresh()
}

function openApply(rule: Rule) {
  applyRule.value = rule
  targetDir.value = ''
  applyPreview.value = null
  applyVisible.value = true
}

/** 规则手动应用到目录：dry-run 预览与真实计划共用后端同一构建路径。 */
async function previewApply() {
  if (!applyRule.value || !targetDir.value.trim()) {
    ElMessage.warning('请填写目标目录')
    return
  }
  applying.value = true
  try {
    applyPreview.value = (await api.dryRun({
      kind: 'RULE_APPLY', refId: applyRule.value.id, targetDir: targetDir.value.trim()
    })).data
  } catch (e) {
    applyPreview.value = null
    ElMessage.error(errText(e))
  } finally {
    applying.value = false
  }
}

async function applyCreate(andExecute: boolean) {
  if (!applyRule.value || !targetDir.value.trim()) return
  applying.value = true
  try {
    const { data: plan } = await api.createPlan({
      kind: 'RULE_APPLY', refId: applyRule.value.id, targetDir: targetDir.value.trim()
    })
    if (andExecute) {
      const { data: executed } = await api.execute(plan.id)
      ElMessage.success(`规则已应用（计划 #${executed.id}：${executed.status}），可到执行中心撤销`)
    } else {
      ElMessage.success(`计划 #${plan.id} 已创建，请到「执行中心」执行`)
    }
    applyVisible.value = false
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    applying.value = false
  }
}

refresh()

defineExpose({ refresh })
</script>

<template>
  <div>
    <el-button type="primary" @click="openCreate">新建规则</el-button>
    <el-table :data="rules" style="margin-top: 12px" size="small">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="条件" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.conditions.map((c: Condition) => condText(c)).join(' 且 ') }}
        </template>
      </el-table-column>
      <el-table-column label="动作" width="260" show-overflow-tooltip>
        <template #default="{ row }">
          {{ ACTION_LABELS[row.action.type] }} → {{ row.action.targetTemplate ?? row.action.tag }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openApply(row)">应用到目录</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="editing?.id ? '编辑规则' : '新建规则'" width="640px">
      <el-form v-if="editing" label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="editing.name" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="editing.priority" :min="1" :max="99" />
          <span class="hint">数字越小越先命中，首条命中即生效</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editing.enabled" />
        </el-form-item>
        <el-form-item v-for="(c, i) in editing.conditions" :key="i" :label="`条件 ${i + 1}`">
          <el-select v-model="c.field" style="width: 170px">
            <el-option v-for="f in fields" :key="f" :value="f" :label="FIELD_LABELS[f]" />
          </el-select>
          <el-select v-model="c.op" style="width: 110px">
            <el-option v-for="o in ops" :key="o" :value="o" :label="OP_LABELS[o]" />
          </el-select>
          <el-select v-if="c.field === 'TYPE'" v-model="c.value" style="flex: 1">
            <el-option v-for="cat in categories" :key="cat" :value="cat" :label="CATEGORY_LABELS[cat]" />
          </el-select>
          <el-input v-else v-model="c.value" style="flex: 1" :placeholder="VALUE_PLACEHOLDERS[c.field]" />
          <el-button text type="danger" @click="editing.conditions.splice(i, 1)">删</el-button>
        </el-form-item>
        <el-form-item>
          <el-button size="small" @click="editing.conditions.push({ field: 'AGE_DAYS', op: 'GTE', value: '30' })">
            加条件
          </el-button>
        </el-form-item>
        <el-form-item label="动作">
          <el-select v-model="editing.action.type" style="width: 120px">
            <el-option v-for="(label, type) in ACTION_LABELS" :key="type" :value="type" :label="label" />
          </el-select>
          <el-input v-if="editing.action.type !== 'TAG'" v-model="editing.action.targetTemplate"
                    placeholder="{type}/{yyyy}/{MM} 或 {name}_已整理{ext}" style="flex: 1" />
          <el-input v-else v-model="editing.action.tag" placeholder="标签名" style="flex: 1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="applyVisible" :title="`应用规则「${applyRule?.name ?? ''}」`" width="min(760px, 94vw)">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="目标目录">
          <el-input v-model="targetDir"
                    placeholder="对最近一次扫描索引中该目录下的文件生效，如 D:/照片"
                    @keyup.enter="previewApply" />
        </el-form-item>
      </el-form>
      <p class="hint">
        先「试运行预览」只做预检不落盘；创建的计划可在「执行中心」执行或撤销。目标目录需先完成一次扫描。
      </p>
      <template v-if="applyPreview">
        <el-alert type="success" :closable="false" style="margin-bottom: 8px"
                  :title="`预检通过：共 ${applyPreview.operations.length} 个操作`" />
        <el-table :data="applyPreview.operations" size="small" max-height="300">
          <el-table-column prop="type" label="操作" width="170" />
          <el-table-column prop="src" label="源" show-overflow-tooltip />
          <el-table-column prop="dst" label="目标" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="error" label="预检信息" show-overflow-tooltip />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="applyVisible = false">关闭</el-button>
        <el-button :loading="applying" @click="previewApply">试运行预览</el-button>
        <el-button type="primary" :loading="applying" :disabled="!applyPreview" @click="applyCreate(false)">
          创建计划
        </el-button>
        <el-button type="danger" :loading="applying" :disabled="!applyPreview" @click="applyCreate(true)">
          创建并执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
