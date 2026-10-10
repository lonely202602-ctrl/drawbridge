<template>
  <div style="max-width: 780px">
    <el-alert type="info" :closable="false" style="margin-bottom: 14px"
              title="运营参数保存后立即热更新生效（转发链路不重启）。分组卡片仅为阅读辅助，保存以整页为准。" />

    <el-card v-for="(items, group) in grouped" :key="group" style="margin-bottom: 14px">
      <template #header><b>{{ group }}</b></template>
      <el-form label-width="280px" size="small">
        <el-form-item v-for="item in items" :key="item.key" :label="item.description || item.key">
          <el-input v-model="values[item.key]" style="width: 180px" />
          <span class="key-hint">{{ item.key }}</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-button type="primary" :loading="saving" @click="save">保存全部</el-button>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { unwrap } from '../api'

const descriptions = ref({})
const values = reactive({})
const saving = ref(false)

const GROUP_NAMES = {
  route: '转发与重试', breaker: '熔断与健康', rate_limit: '限流冷却',
  poll: '任务轮询与租约', submit: '提交超时', task: '任务生命周期',
  retention: '数据保留', openapi: '业务方限流'
}

const grouped = computed(() => {
  const g = {}
  for (const key of Object.keys(descriptions.value)) {
    const prefix = key.split('.')[0]
    const name = GROUP_NAMES[prefix] || prefix
    g[name] = g[name] || []
    g[name].push({ key, description: descriptions.value[key] })
  }
  return g
})

async function load() {
  const data = await unwrap(api.get('/admin/settings'))
  for (const [k, v] of Object.entries(data.values || {})) {
    values[k] = v
  }
  descriptions.value = data.descriptions || {}
}

async function save() {
  saving.value = true
  try {
    await unwrap(api.put('/admin/settings', { ...values }))
    ElMessage.success('已保存并热更新生效')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.key-hint { margin-left: 10px; color: #bbb; font-size: 12px; }
</style>
