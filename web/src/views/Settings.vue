<template>
  <div style="max-width: 780px">
    <div class="alert info">
      运营参数保存后立即热更新生效（转发链路不重启）。分组卡片仅为阅读辅助，保存以整页为准。
    </div>

    <div v-for="(items, group) in grouped" :key="group" class="db-card" style="margin-bottom: 14px; padding: 14px 20px 18px">
      <b style="font-size: 13.5px">{{ group }}</b>
      <div style="margin-top: 10px">
        <div v-for="item in items" :key="item.key" class="set-row">
          <div class="set-label" :title="item.key">{{ item.description || item.key }}</div>
          <div style="display: flex; align-items: center; gap: 10px">
            <input v-model="values[item.key]" class="input" style="width: 180px" />
            <code class="key-hint">{{ item.key }}</code>
          </div>
        </div>
      </div>
    </div>

    <button class="btn primary" :disabled="saving" @click="save">
      <span v-if="saving" class="spin"></span>保存全部
    </button>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { toast } from '../ui'
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
    toast('已保存并热更新生效')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.set-row {
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
  padding: 7px 0;
}
.set-label { font-size: 13px; color: var(--ink); }
.key-hint { opacity: 0.55; font-size: 11.5px; }
</style>
