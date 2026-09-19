<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errText } from '../api'
import type { Condition, Rule } from '../types'

const rules = ref<Rule[]>([])
const visible = ref(false)
const editing = ref<Rule | null>(null)
const saving = ref(false)

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

async function refresh() {
  rules.value = (await api.rules()).data
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
  await ElMessageBox.confirm(`删除规则「${rule.name}」？`, '确认', { type: 'warning' })
  await api.deleteRule(rule.id)
  await refresh()
}

refresh()
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
          {{ row.conditions.map((c: Condition) => `${c.field} ${c.op} ${c.value}`).join(' 且 ') }}
        </template>
      </el-table-column>
      <el-table-column label="动作" width="260" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.action.type }} → {{ row.action.targetTemplate ?? row.action.tag }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
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
            <el-option v-for="f in fields" :key="f" :value="f" :label="f" />
          </el-select>
          <el-select v-model="c.op" style="width: 100px">
            <el-option v-for="o in ops" :key="o" :value="o" :label="o" />
          </el-select>
          <el-input v-model="c.value" style="flex: 1" placeholder="值" />
          <el-button text type="danger" @click="editing.conditions.splice(i, 1)">删</el-button>
        </el-form-item>
        <el-form-item>
          <el-button size="small" @click="editing.conditions.push({ field: 'AGE_DAYS', op: 'GTE', value: '30' })">
            加条件
          </el-button>
        </el-form-item>
        <el-form-item label="动作">
          <el-select v-model="editing.action.type" style="width: 120px">
            <el-option value="MOVE" label="MOVE 移动" />
            <el-option value="RENAME" label="RENAME 重命名" />
            <el-option value="TAG" label="TAG 标记" />
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
  </div>
</template>

<style scoped>
.hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
