<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="5" v-for="c in cards" :key="c.label">
        <el-card><div class="stat-label">{{ c.label }}</div><div class="stat-value">{{ c.value }}</div></el-card>
      </el-col>
    </el-row>

    <h3>渠道健康</h3>
    <el-table :data="channels" size="small" border>
      <el-table-column prop="name" label="渠道" />
      <el-table-column prop="protocol" label="协议" />
      <el-table-column label="启停" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="熔断" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.health === 2" type="danger">熔断中</el-tag>
          <el-tag v-else-if="row.health === 3" type="warning">半开探测</el-tag>
          <el-tag v-else type="success">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="冷却" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.cooling" type="warning">冷却中</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="statusReason" label="原因" />
    </el-table>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api, { unwrap } from '../api'

const data = ref({})
const timer = ref(null)
const cards = computed(() => [
  { label: '今日任务', value: data.value.todayTotal ?? '-' },
  { label: '今日失败', value: data.value.todayFailed ?? '-' },
  { label: '在途任务', value: data.value.inflight ?? '-' },
  { label: '最老在途(秒)', value: data.value.oldestPendingSec ?? '-' },
  { label: '上游并发中', value: data.value.globalUpstreamInflight ?? '-' }
])
const channels = computed(() => data.value.channels || [])

function statusTag(s) {
  return { 1: { type: 'success', text: '正常' }, 2: { type: 'danger', text: '熔断' },
    3: { type: 'warning', text: '半开' }, 4: { type: 'danger', text: '余额停用' },
    0: { type: 'info', text: '手动停用' } }[s] || { type: 'info', text: s }
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

<style scoped>
.stat-label { color: #888; font-size: 13px; }
.stat-value { font-size: 26px; font-weight: bold; margin-top: 6px; }
</style>
