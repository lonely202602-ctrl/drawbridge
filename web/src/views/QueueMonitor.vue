<template>
  <div>
    <div class="stat-strip">
      <div class="stat-cell">
        <div><div class="num">{{ totals.queued }}</div><div class="lbl">排队中（SUBMITTED）</div></div>
      </div>
      <div class="stat-cell">
        <div><div class="num" style="color: #a4712a">{{ totals.running }}</div><div class="lbl">在跑（RUNNING）</div></div>
      </div>
      <div class="stat-cell">
        <div><div class="num">{{ totals.free }}<span class="num-sub"> / {{ totals.capacity }}</span></div><div class="lbl">空闲槽位 / 账号总容量</div></div>
      </div>
      <div class="stat-cell">
        <div><div class="num num-muted">{{ totals.globalMax }}</div><div class="lbl">全网在途上限</div></div>
      </div>
    </div>

    <h3 class="sec">上游账号并发池 <span class="count-chip">{{ accounts.length }}</span>
      <span class="hint" style="margin-left: 10px; font-weight: 400">同一 base_url + Key 的渠道共享一个并发池；容量 0 = 暂停派发</span>
    </h3>
    <div class="acct-wall">
      <div v-for="a in accounts" :key="a.accountKey" class="db-card acct">
        <div class="acct-head">
          <div class="acct-name" :title="a.channels.map(c => c.name).join(' / ')">{{ acctTitle(a) }}</div>
          <span v-if="a.paused" class="badge gray">已暂停</span>
          <span v-else-if="a.free > 0" class="badge green">空闲 {{ a.free }}</span>
          <span v-else class="badge red">已满</span>
        </div>
        <div class="acct-key">账号 <span class="mono">{{ a.accountKey.slice(0, 8) }}</span></div>
        <div class="cap-track">
          <div class="cap-fill" :style="{ width: capPct(a) + '%', background: capColor(a) }"></div>
        </div>
        <div class="cap-line">在跑 <b>{{ a.running }}</b> / 容量 {{ a.capacity }}<span v-if="!a.paused"> · 空闲 {{ a.free }}</span></div>
        <div class="ch-rows">
          <div v-for="c in a.channels" :key="c.id" class="ch-row">
            <span class="dot" :class="dotClass(c)"></span>
            <span class="ch-name">{{ c.name }}</span>
            <span v-if="c.enabled !== 1" class="badge gray">停用</span>
            <span v-else-if="c.status === 4" class="badge red">余额关停</span>
            <span v-else-if="c.status === 2" class="badge red">熔断</span>
            <span v-else-if="c.status === 3" class="badge orange">半开</span>
          </div>
          <div v-if="!a.channels.length" class="hint">未绑定渠道</div>
        </div>
        <div v-if="a.pendingBatches?.length" class="batch-box">
          <div class="hint" style="color: #b4523f">该账号有渠道被余额关停，充值后可整批恢复：</div>
          <button v-for="b in a.pendingBatches" :key="b.id" class="btn sm" style="margin: 6px 6px 0 0"
                  @click="restoreBatch(b)">
            恢复批次 #{{ b.id }}（{{ b.reason }}）
          </button>
        </div>
      </div>
      <div v-if="!accounts.length" class="db-card hint" style="text-align: center; padding: 30px">
        暂无上游账号（保存渠道后自动生成）
      </div>
    </div>

    <h3 class="sec">分组排队</h3>
    <table class="xtable">
      <thead>
        <tr>
          <th>分组（业务方 model 名）</th>
          <th style="width: 120px">策略</th>
          <th style="width: 90px">排队数</th>
          <th style="width: 140px">最老排队</th>
          <th style="width: 110px">状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="g in groups" :key="g.groupCode">
          <td>{{ g.groupCode }}</td>
          <td class="hint">{{ strategyLabel(g.strategy) }}</td>
          <td :style="g.queued > 0 ? 'color:#a4712a; font-weight:700' : ''">{{ g.queued }}</td>
          <td>{{ fmtWait(g.oldestQueuedSeconds) }}</td>
          <td>
            <span v-if="g.enabled !== 1" class="badge gray">已停用</span>
            <span v-else-if="g.queued > 0" class="badge orange">排队中</span>
            <span v-else class="badge green">通畅</span>
          </td>
        </tr>
        <tr v-if="!groups.length"><td colspan="5" class="hint" style="text-align:center">暂无分组</td></tr>
      </tbody>
    </table>

    <h3 class="sec">在跑名单 <span class="hint" style="font-weight: 400">（最近 50，含已受理等出图的轮询期）</span></h3>
    <table class="xtable">
      <thead>
        <tr>
          <th>任务号</th>
          <th>分组</th>
          <th style="width: 260px">渠道</th>
          <th style="width: 130px">阶段</th>
          <th style="width: 140px">最近活动</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in runningTasks" :key="t.taskId" class="row-in">
          <td class="mono">{{ t.taskId }}</td>
          <td>{{ t.groupCode }}</td>
          <td class="ellipsis" style="max-width: 260px">{{ channelLabel(t.channelId) }}</td>
          <td>
            <span v-if="t.hasUpstreamTaskId" class="badge green">轮询期 · 已受理</span>
            <span v-else class="badge orange">提交中</span>
          </td>
          <td class="hint">{{ fmtAgo(t.updatedAt) }}</td>
        </tr>
        <tr v-if="!runningTasks.length"><td colspan="5" class="hint" style="text-align:center; padding: 22px">当前没有在跑任务</td></tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api, { unwrap } from '../api'

const data = ref({})
const timer = ref(null)
const accounts = computed(() => data.value.accounts || [])
const groups = computed(() => data.value.groups || [])
const runningTasks = computed(() => data.value.runningTasks || [])
const totals = computed(() => {
  const t = data.value.totals || {}
  const capacity = accounts.value.reduce((s, a) => s + (a.capacity || 0), 0)
  const running = accounts.value.reduce((s, a) => s + (a.running || 0), 0)
  return { queued: t.queued ?? 0, running: t.running ?? running, globalMax: t.globalMax ?? '-', free: Math.max(0, capacity - running), capacity }
})
const configLine = computed(() => {
  const c = data.value.config || {}
  const parts = []
  if (c.queueMaxWaitMinutes != null) parts.push(`排队死线 ${c.queueMaxWaitMinutes} 分钟`)
  if (c.tokenMaxQueued != null) parts.push(`单令牌排队上限 ${c.tokenMaxQueued}`)
  return parts.length ? `（${parts.join(' · ')}，设置页可调）` : ''
})

const channelNames = computed(() => {
  const m = {}
  for (const a of accounts.value) for (const c of a.channels || []) m[c.id] = c.name
  return m
})
function channelLabel(id) {
  if (id == null) return '-'
  return `#${id} · ${channelNames.value[id] || '已删除渠道'}`
}
function acctTitle(a) {
  const names = (a.channels || []).filter(c => c.enabled === 1).map(c => c.name)
  const pool = names.length ? names : (a.channels || []).map(c => c.name)
  return pool.length ? pool.join(' / ') : '未绑定渠道'
}
function capPct(a) {
  if (!a.capacity) return 0
  return Math.min(100, Math.round(a.running * 100 / a.capacity))
}
function capColor(a) {
  if (a.paused) return '#c9b99a'
  const pct = capPct(a)
  return pct >= 100 ? '#b4523f' : (pct >= 70 ? '#a4712a' : '#5c7a5c')
}
function dotClass(c) {
  if (c.enabled !== 1) return 'gray'
  return c.health === 'normal' ? 'green' : 'red'
}
const STRATEGY = {
  FAILOVER: '故障转移', POLLING: '轮询', RANDOM: '随机', WEIGHTED: '加权分配', IDLE_FIRST: '空闲优先'
}
function strategyLabel(s) { return STRATEGY[s] || s || '-' }
function fmtWait(sec) {
  if (!sec || sec <= 0) return '-'
  if (sec < 60) return sec + ' 秒'
  return Math.floor(sec / 60) + ' 分 ' + String(sec % 60).padStart(2, '0') + ' 秒'
}
function fmtAgo(t) {
  if (!t) return '-'
  const then = new Date(t).getTime()
  if (Number.isNaN(then)) return String(t).replace('T', ' ').slice(0, 19)
  const sec = Math.max(0, Math.round((Date.now() - then) / 1000))
  if (sec < 60) return sec + ' 秒前'
  return Math.floor(sec / 60) + ' 分 ' + String(sec % 60).padStart(2, '0') + ' 秒前'
}
async function restoreBatch(b) {
  if (!window.confirm(`确认整批恢复批次 #${b.id}（该账号全部渠道与联动成员）？`)) return
  await unwrap(api.post(`/admin/channels/disable-batches/${b.id}/restore`))
  await load()
}
async function load() {
  data.value = await unwrap(api.get('/admin/queue-monitor/overview'))
}
onMounted(() => {
  load()
  timer.value = setInterval(load, 5000)
})
onBeforeUnmount(() => clearInterval(timer.value))
</script>

<style scoped>
.sec { margin: 26px 4px 12px; display: flex; align-items: center; }
.count-chip {
  font-size: 12px; color: var(--ink2); background: var(--line-soft);
  border-radius: 10px; padding: 1px 9px; margin-left: 6px; font-weight: 600;
}
.num-sub { font-size: 14px; color: var(--ink2); font-weight: 500; }
.num-muted { color: var(--ink2); }
.acct-wall {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap: 14px;
}
.acct { padding: 16px 18px; }
.acct-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.acct-name { font-weight: 700; font-size: 14.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.acct-key { font-size: 12px; color: var(--ink2); margin: 3px 0 10px; }
.cap-track { height: 8px; background: #efece3; border-radius: 4px; overflow: hidden; }
.cap-fill { height: 100%; border-radius: 4px; transition: width 0.4s ease; }
.cap-line { font-size: 12.5px; color: var(--ink2); margin-top: 7px; }
.cap-line b { color: var(--ink); }
.ch-rows { margin-top: 10px; display: flex; flex-direction: column; gap: 5px; }
.ch-row { display: flex; align-items: center; gap: 7px; font-size: 13px; }
.ch-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.dot.green { background: #5c7a5c; }
.dot.red { background: #b4523f; }
.dot.gray { background: #c2c0b4; }
.batch-box { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--line); }
.mono { font-family: var(--mono, monospace); font-size: 12px; }
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
