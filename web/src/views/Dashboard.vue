<template>
  <div>
    <div class="stat-strip">
      <div class="stat-cell"><div><div class="num">{{ data.todayTotal ?? '-' }}</div><div class="lbl">今日任务</div></div></div>
      <div class="stat-cell"><div><div class="num" style="color: #b4523f">{{ data.todayFailed ?? '-' }}</div><div class="lbl">今日失败</div></div></div>
      <div class="stat-cell"><div><div class="num" style="color: #a4712a">{{ data.inflight ?? '-' }}</div><div class="lbl">在途任务</div></div></div>
      <div class="stat-cell"><div><div class="num" :style="data.oldestPendingSec > 60 ? 'color:#b4523f' : ''">{{ data.oldestPendingSec ?? '-' }}s</div><div class="lbl">最老在途</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ data.globalUpstreamInflight ?? '-' }}</div><div class="lbl">上游并发中</div></div></div>
    </div>

    <h3 style="margin: 20px 4px 12px">渠道健康</h3>
    <div class="card-wall">
      <div v-for="c in channels" :key="c.id" class="db-card">
        <div style="display: flex; justify-content: space-between; align-items: center">
          <b>{{ c.name }}</b>
          <span class="badge" :class="healthBadge(c)">{{ healthText(c) }}</span>
        </div>
        <div class="hint" style="margin-top: 8px">
          {{ protoLabel(c.protocol) }} ·
          <span v-if="c.statusReason">{{ c.statusReason }}</span>
          <span v-else-if="c.enabled === 1">调度正常</span>
          <span v-else>已停用</span>
        </div>
      </div>
      <div v-if="!channels.length" class="db-card hint" style="text-align: center; padding: 30px">
        暂无渠道数据
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api, { unwrap } from '../api'

const data = ref({})
const timer = ref(null)
const channels = computed(() => data.value.channels || [])

const protoLabel = v => ({ OPENAI_IMAGES_SYNC: 'OpenAI 同步', OPENAI_ASYNC_TASK: 'OpenAI 异步', CRUN_TASK: 'Crun', VOLCANO_ARK: '火山方舟' }[v] || v)

function healthText(c) {
  if (c.enabled !== 1 || c.status === 0) return '手动停用'
  if (c.status === 4) return '余额停用'
  if (c.health === 2 || c.status === 2) return '熔断中'
  if (c.health === 3 || c.status === 3) return '半开探测'
  if (c.cooling) return '限流冷却'
  return '正常'
}
function healthBadge(c) {
  const t = healthText(c)
  return t === '正常' ? 'green' : (t === '手动停用' ? 'gray' : (t === '半开探测' || t === '限流冷却' ? 'orange' : 'red'))
}

async function load() {
  data.value = await unwrap(api.get('/admin/dashboard'))
}

onMounted(() => {
  load()
  timer.value = setInterval(load, 10000)
})
onBeforeUnmount(() => clearInterval(timer.value))
</script>
