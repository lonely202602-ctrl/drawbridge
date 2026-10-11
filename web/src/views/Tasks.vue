<template>
  <div>
    <div class="db-card" style="padding: 14px 18px; margin-bottom: 14px">
      <div style="display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end">
        <div class="field" style="margin: 0">
          <label>任务号</label>
          <input v-model="query.taskId" class="input" placeholder="模糊搜索" style="width: 200px" />
        </div>
        <div class="field" style="margin: 0">
          <label>状态</label>
          <XSelect v-model="query.status" clearable :options="statusOptions" style="width: 150px" />
        </div>
        <div class="field" style="margin: 0">
          <label>分组</label>
          <input v-model="query.groupCode" class="input" placeholder="分组编码" style="width: 130px" />
        </div>
        <button class="btn primary" @click="load(1)">查询</button>
      </div>
    </div>

    <div class="db-card" style="padding: 6px 12px">
      <table class="xtable">
        <thead>
          <tr>
            <th>任务号</th><th>分组</th><th>状态</th><th>受理渠道</th>
            <th>重试</th><th>错误信息</th><th>创建时间</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td class="mono">{{ row.taskId }}</td>
            <td>{{ row.groupCode }}</td>
            <td><span class="tag" :class="{ SUCCEEDED: 'g', FAILED: 'r', RUNNING: 'o', SUBMITTED: 'gr' }[row.status]">{{ row.status }}</span></td>
            <td>{{ row.channelName || '-' }}</td>
            <td>{{ row.attemptCount }}</td>
            <td class="hint ellipsis" style="max-width: 260px" :title="row.errorMsg || ''">{{ row.errorMsg || '-' }}</td>
            <td class="hint">{{ row.createdAt }}</td>
            <td><button class="btn text sm" @click="openDetail(row)">详情 →</button></td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="8" style="text-align: center; color: var(--ink2); padding: 30px">没有匹配的任务</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button :disabled="query.page <= 1" @click="load(query.page - 1)">‹</button>
      <span>第 {{ query.page }} 页 / 共 {{ totalPages || 1 }} 页 · {{ total }} 条</span>
      <button :disabled="query.page >= totalPages" @click="load(query.page + 1)">›</button>
    </div>

    <!-- 任务详情：重试时间线 + 双向请求体 + 结果 -->
    <XDialog v-model="detailVisible" title="任务详情" width="860px">
      <template v-if="detail">
        <h4 style="margin: 2px 0 8px">重试时间线（每次实际调用了哪个上游）</h4>
        <table class="xtable">
          <thead>
            <tr>
              <th style="width: 40px">#</th><th style="width: 70px">阶段</th><th>上游</th>
              <th>模型</th><th style="width: 88px">结果</th><th>错误类别</th>
              <th style="width: 74px">耗时ms</th><th>请求地址</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in detail.attempts" :key="a.attemptNo">
              <td>{{ a.attemptNo }}</td>
              <td class="hint">{{ a.phase }}</td>
              <td>{{ a.channelName }}</td>
              <td class="mono ellipsis" style="max-width: 150px" :title="a.upstreamModel">{{ a.upstreamModel }}</td>
              <td><span class="tag" :class="a.outcome === 'SUCCESS' ? 'g' : 'r'">
                {{ a.phase === 'SUBMIT' && a.outcome === 'SUCCESS' ? '受理成功' : a.outcome }}</span></td>
              <td class="hint ellipsis" style="max-width: 140px" :title="a.errorMsg || ''">{{ a.errorKind || '-' }}</td>
              <td>{{ a.costMs ?? '-' }}</td>
              <td class="hint mono ellipsis" style="max-width: 220px" :title="a.upstreamUrl || ''">{{ a.upstreamUrl || '-' }}</td>
            </tr>
            <tr v-if="!detail.attempts.length"><td colspan="8" class="hint" style="text-align: center">无尝试记录</td></tr>
          </tbody>
        </table>
        <div class="hint" style="margin: 8px 0 4px">
          SUBMIT 阶段的「受理成功」= 上游已接收任务并返回任务号，图片由网关后台轮询推进；任务最终结果以上方任务状态为准。
        </div>

        <h4 style="margin: 16px 0 6px">① 业务方请求体</h4>
        <pre class="json-block">{{ pretty(detail.task.bizRequest) }}</pre>
        <h4 style="margin: 14px 0 6px">② 封装后发上游的请求体</h4>
        <div v-if="detail.task.upstreamUrl" class="hint" style="margin-bottom: 4px; word-break: break-all">
          请求地址：{{ detail.task.upstreamUrl }}
        </div>
        <pre class="json-block">{{ pretty(detail.task.upstreamRequest) }}</pre>
        <h4 style="margin: 14px 0 6px">③ 任务响应结果</h4>
        <pre class="json-block">{{ pretty(detail.task.result) || '（无）' }}</pre>
        <div v-if="detail.task.errorMsg" style="color: var(--danger); margin-top: 8px">失败原因：{{ detail.task.errorMsg }}</div>
      </template>
    </XDialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import XDialog from '../components/XDialog.vue'
import XSelect from '../components/XSelect.vue'
import api, { unwrap } from '../api'

const rows = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, status: '', groupCode: '', taskId: '' })
const detailVisible = ref(false)
const detail = ref(null)
const statusOptions = ['SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED'].map(s => ({ label: s, value: s }))
const totalPages = computed(() => Math.ceil(total.value / query.size))

async function load(page) {
  if (page) query.page = page
  const data = await unwrap(api.get('/admin/tasks', { params: { ...query } }))
  rows.value = data.records
  total.value = data.total
}

async function openDetail(row) {
  detail.value = await unwrap(api.get(`/admin/tasks/detail/${row.id}`))
  detailVisible.value = true
}

function pretty(json) {
  if (!json) return ''
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch (e) { return json }
}

onMounted(() => load())
</script>

<style scoped>
.json-block {
  background: #f7f6f1;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  font: 12px/1.7 var(--mono);
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
