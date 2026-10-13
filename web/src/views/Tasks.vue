<template>
  <div>
    <div class="db-card" style="padding: 14px 18px; margin-bottom: 14px">
      <div style="display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end">
        <div class="field" style="margin: 0">
          <label>任务号</label>
          <input v-model="draft.taskId" class="input" placeholder="模糊搜索" style="width: 200px" />
        </div>
        <div class="field" style="margin: 0">
          <label>状态</label>
          <XSelect v-model="draft.status" clearable :options="statusOptions" style="width: 150px" />
        </div>
        <div class="field" style="margin: 0">
          <label>分组</label>
          <input v-model="draft.groupCode" class="input" placeholder="分组编码" style="width: 130px" />
        </div>
        <button class="btn primary" @click="search">查询</button>
        <label class="hint" style="display: flex; align-items: center; gap: 6px; margin: 0 0 2px; cursor: pointer; user-select: none">
          <input type="checkbox" v-model="auto" /> 每 5 秒自动刷新，新任务自动亮起
        </label>
      </div>
    </div>

    <div class="db-card" style="padding: 6px 12px">
      <table class="xtable">
        <thead>
          <tr>
            <th>任务号</th><th>分组</th><th>状态</th><th>受理渠道</th>
            <th>重试</th><th style="width: 150px">错误码</th><th>错误信息</th><th>创建时间</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" class="row-in">
            <td class="mono">{{ row.taskId }}</td>
            <td>{{ row.groupCode }}</td>
            <td><span class="tag" :class="{ SUCCEEDED: 'g', FAILED: 'r', RUNNING: 'o', SUBMITTED: 'gr' }[row.status]">{{ row.status }}</span></td>
            <td>{{ row.channelName || '-' }}</td>
            <td>{{ row.attemptCount }}</td>
            <td class="mono hint">{{ row.errorCode || '-' }}</td>
            <td class="hint ellipsis" style="max-width: 220px" :title="row.errorMsg || ''">{{ row.errorMsg || '-' }}</td>
            <td class="hint">{{ row.createdAt }}</td>
            <td><button class="btn text sm" @click="openDetail(row)">详情 →</button></td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="9" style="text-align: center; color: var(--ink2); padding: 30px">没有匹配的任务</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button :disabled="applied.page <= 1" @click="load(applied.page - 1)">‹</button>
      <span>第 {{ applied.page }} 页 / 共 {{ totalPages || 1 }} 页 · {{ total }} 条</span>
      <button :disabled="applied.page >= totalPages" @click="load(applied.page + 1)">›</button>
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
        <div v-if="detail.task.upstreamTaskId" class="hint"
             :style="detail.task.status === 'RUNNING' ? 'margin: 0 0 4px; color: var(--accent)' : 'margin: 0 0 4px'">
          <template v-if="detail.task.status === 'RUNNING'">
            轮询进行中：已轮询 {{ detail.task.pollCount ?? 0 }} 次 · 最近轮询 {{ fmtTime(detail.task.lastPollAt) }} · 下次轮询 {{ fmtTime(detail.task.nextPollTime) }}
          </template>
          <template v-else>
            已轮询 {{ detail.task.pollCount ?? 0 }} 次 · 最近轮询 {{ fmtTime(detail.task.lastPollAt) }}
          </template>
        </div>

        <template v-if="detailImages.length">
          <h4 style="margin: 16px 0 6px">结果图（{{ detailImages.length }} 张 · 点击放大 · 过期失效的不显示）</h4>
          <div style="display: flex; gap: 10px; flex-wrap: wrap">
            <template v-for="(u, i) in detailImages" :key="detail.task.id + '-' + i">
              <img v-if="!imgFailed[i]" :src="u" alt="结果图" @error="onImgError(i)" @click="openImage(u)"
                   style="max-width: 260px; max-height: 260px; border-radius: 8px; border: 1px solid var(--line); background: #fff; cursor: zoom-in; object-fit: contain" />
            </template>
          </div>
          <div v-if="!visibleImageCount" class="hint" style="margin-top: 4px">
            结果图已失效（上游临时 URL 过期），原始记录见下方任务响应结果
          </div>
        </template>

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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import XDialog from '../components/XDialog.vue'
import XSelect from '../components/XSelect.vue'
import api, { unwrap } from '../api'

const rows = ref([])
const total = ref(0)
const auto = ref(true)
// draft = 输入框草稿；applied = 已生效条件（自动刷新按 applied 重查，打了一半的词不会被误当条件）
const draft = reactive({ status: '', groupCode: '', taskId: '' })
const applied = reactive({ page: 1, size: 20, status: '', groupCode: '', taskId: '' })
const detailVisible = ref(false)
const detail = ref(null)
const statusOptions = ['SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED'].map(s => ({ label: s, value: s }))
const totalPages = computed(() => Math.ceil(total.value / applied.size))

async function load(page) {
  if (page) applied.page = page
  const data = await unwrap(api.get('/admin/tasks', { params: { ...applied } }))
  rows.value = data.records
  total.value = data.total
}

function search() {
  Object.assign(applied, { ...draft, page: 1 })
  load()
}

async function openDetail(row) {
  detail.value = await unwrap(api.get(`/admin/tasks/detail/${row.id}`))
  imgFailed.value = {}
  detailVisible.value = true
}

// 结果图：从 result JSON 里取 urls 渲染；上游临时 URL 过期（加载失败）就隐藏该张，全部失效给灰提示
const imgFailed = ref({})
const detailImages = computed(() => {
  const raw = detail.value?.task?.result
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed?.urls) ? parsed.urls.filter(u => typeof u === 'string' && u) : []
  } catch (e) {
    return []
  }
})
const visibleImageCount = computed(() => detailImages.value.length - Object.keys(imgFailed.value).length)
function onImgError(i) {
  imgFailed.value = { ...imgFailed.value, [i]: true }
}
function openImage(u) {
  if (u.startsWith('http')) window.open(u, '_blank')
}

/** 自动刷新节拍：详情弹窗开着时不动列表，只续非终态任务的详情（轮询次数/时间线跟着走） */
async function tick() {
  if (!auto.value) return
  try {
    if (detailVisible.value && detail.value) {
      const st = detail.value.task.status
      if (st === 'SUBMITTED' || st === 'RUNNING') {
        detail.value = await unwrap(api.get(`/admin/tasks/detail/${detail.value.task.id}`))
      }
      return
    }
    await load()
  } catch (e) { /* 单次刷新失败静默，下个节拍再试 */ }
}

function pretty(json) {
  try {
    return json ? JSON.stringify(JSON.parse(json), null, 2) : ''
  } catch (e) {
    return json || ''
  }
}

/** LocalDateTime 序列化串 → 本地展示（yyyy-MM-dd HH:mm:ss），空值显示 - */
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

let timer = null
onMounted(() => {
  load()
  timer = setInterval(tick, 5000)
})
onBeforeUnmount(() => clearInterval(timer))
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
/* 新任务插入动画：滑入 + 绿光渐隐（按 key 挂载时只播一次，老行复用 DOM 不重播） */
.row-in { animation: row-slide 0.4s ease-out, row-glow 1.8s ease-out; }
@keyframes row-slide {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: none; }
}
@keyframes row-glow {
  0% { background-color: var(--accent-wash, #eef3ea); }
  100% { background-color: transparent; }
}
</style>
