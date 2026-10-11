<template>
  <div>
    <!-- 总览 -->
    <div class="stat-strip">
      <div class="stat-cell"><div><div class="num">{{ rows.length }}</div><div class="lbl">全部渠道</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ rows.filter(r => r.enabled === 1 && r.status === 1).length }}</div><div class="lbl">正常服务</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ rows.filter(r => r.health === 2).length }}</div><div class="lbl">熔断中</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ rows.filter(r => r.status === 4).length }}</div><div class="lbl">余额停用</div></div></div>
      <div style="margin-left: auto; align-self: center">
        <button class="btn primary" @click="openWizard">＋ 接入上游</button>
      </div>
    </div>

    <!-- 渠道卡片墙 -->
    <div class="card-wall">
      <div v-for="row in rows" :key="row.id" class="db-card">
        <div style="display: flex; justify-content: space-between; align-items: center">
          <div style="display: flex; align-items: center; gap: 8px">
            <b style="font-size: 16px">{{ row.name }}</b>
            <span class="badge" :class="protoBadge(row.protocol)">{{ protoLabel(row.protocol) }}</span>
          </div>
          <span class="badge" :class="statusBadge(row)">{{ statusText(row) }}</span>
        </div>
        <div class="hint" style="margin: 8px 0 4px; word-break: break-all">🔗 {{ row.baseUrl }}</div>
        <div class="hint">Key {{ row.keyConfigured ? '已配置' : '未配置' }} · 并发 {{ row.maxConcurrency || 5 }}
          <span v-if="row.statusReason"> · {{ row.statusReason }}</span></div>
        <div style="display: flex; gap: 6px; margin-top: 12px; flex-wrap: wrap">
          <button class="btn ghost sm" @click="testRun(row)">试跑</button>
          <button class="btn ghost sm" @click="manageModels(row)">模型</button>
          <button class="btn ghost sm" v-if="row.protocol === 'OPENAI_IMAGES_SYNC' || row.protocol === 'OPENAI_ASYNC_TASK'"
                  @click="syncModels(row)">同步模型</button>
          <button class="btn ghost sm" @click="openEdit(row)">编辑</button>
          <button class="btn primary sm" v-if="row.enabled !== 1 || row.status !== 1"
                  @click="enable(row)">恢复启用</button>
          <button class="btn danger sm" v-else @click="disable(row)">停用</button>
        </div>
      </div>
      <div v-if="!rows.length" class="db-card" style="text-align: center; color: var(--ink2); padding: 40px">
        还没有渠道，点右上角「接入上游」开始
      </div>
    </div>

    <!-- 接入向导 -->
    <XDialog v-model="wizardVisible" :title="'接入上游'" :width="'660px'">
      <div class="hint" style="margin-bottom: 12px">三步：选协议 → 填接入点和 Key → 试跑验收</div>
      <div class="steps">
        <div class="step" :class="{ on: step === 0, done: step > 0 }"><span class="n">{{ step > 0 ? '✓' : '1' }}</span>选协议</div>
        <div class="step-bar" :class="{ done: step > 0 }"></div>
        <div class="step" :class="{ on: step === 1, done: step > 1 }"><span class="n">{{ step > 1 ? '✓' : '2' }}</span>填信息</div>
        <div class="step-bar" :class="{ done: step > 1 }"></div>
        <div class="step" :class="{ on: step === 2 }"><span class="n">3</span>试跑</div>
      </div>

      <!-- Step 1: 选协议 -->
      <div v-if="step === 0">
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px">
          <div v-for="p in protocols" :key="p.value" class="proto-card" :class="{ active: w.protocol === p.value }"
               :style="p.disabled ? 'opacity:.45; cursor: not-allowed' : ''"
               @click="pickProtocol(p)">
            <b>{{ p.label }}</b>
            <small>{{ p.desc }}</small>
          </div>
        </div>
        <hr class="divider" data-label="上游文档 curl（可选，粘贴后自动识别接入点）">
        <textarea v-model="w.curlText" class="input" rows="4"
                  placeholder="提交接口的 curl，例如：&#10;curl -X POST 'https://api.crunai.com/api/v1/client/job/CreateTask' -H 'x-api-key: sk-xxx' -d '{...}'"></textarea>
        <div v-if="w.protocol === 'OPENAI_ASYNC_TASK' || w.protocol === 'CRUN_TASK'" style="margin-top: 10px">
          <textarea v-model="w.queryCurlText" class="input" rows="3"
                    placeholder="查询接口的 curl（可选，模板已内置默认查询路径，不一致时粘贴这条自动纠正）：&#10;curl 'https://api.crunai.com/api/v1/client/job/TaskInfo?task_id=xxx' -H 'x-api-key: sk-xxx'"></textarea>
        </div>
        <div v-if="curlParsed" class="alert success" style="margin-top: 10px">
          已识别接入点：<b>{{ curlParsed.base }}</b>
          <span v-if="curlParsed.path">，提交路径 {{ curlParsed.path }}</span>
          <span v-if="queryParsed?.path">，查询路径 {{ queryParsed.path }}</span>
        </div>
        <button class="btn primary block" :disabled="!w.protocol" style="margin-top: 16px"
                @click="applyCurlAndNext">下一步</button>
      </div>

      <!-- Step 2: 填信息 -->
      <div v-if="step === 1">
        <div v-if="curlNotices.length" class="alert warning">
          <div style="font-weight: 600; margin-bottom: 4px">向导根据你贴的 curl 自动做了以下调整（可在下方高级选项里修改）：</div>
          <div v-for="(n, i) in curlNotices" :key="i" style="font-size: 12px; line-height: 1.9">· {{ n }}</div>
        </div>
        <div class="form-grid">
          <label>渠道名称</label>
          <input v-model="w.name" class="input" placeholder="如：Crun 官方 / API易" />
          <label>{{ w.protocol === 'CRUN_TASK' ? '接入点' : 'Base URL' }}</label>
          <div>
            <input v-model="w.baseUrl" class="input" :placeholder="currentProto.placeholder" />
            <div class="hint" style="margin-top: 4px">{{ currentProto.baseUrlHint }}</div>
          </div>
          <label>API Key</label>
          <input v-model="w.apiKey" class="input" type="password" placeholder="上游密钥，加密存储" autocomplete="new-password" />
          <label>并发上限</label>
          <input v-model.number="w.maxConcurrency" class="input" type="number" min="1" max="100" style="width: 120px" />
          <label>备注</label>
          <input v-model="w.remark" class="input" />

          <template v-if="(w.protocol === 'OPENAI_IMAGES_SYNC' || w.protocol === 'OPENAI_ASYNC_TASK') && w.editsSubmitPath">
            <label>接口确认</label>
            <div>
              <div class="hint" style="margin-bottom: 6px">
                已识别到<b>图生图</b>端点：请核对两条 URL（不带参考图的<b>文生图</b>永远走模板默认，带参考图才走图生图端点）
              </div>
              <input v-model="w.submitPath" class="input" placeholder="留空 = 模板默认" />
              <div class="hint" style="margin: 2px 0 6px">↑ 文生图</div>
              <input v-model="w.editsSubmitPath" class="input" placeholder="如 /v1/images/edits" />
              <div class="hint" style="margin-top: 2px">↑ 图生图</div>
            </div>
          </template>
        </div>

        <button class="collapse-toggle" style="margin: 6px 0 10px" @click="advOpen = !advOpen">
          <span class="arr" :class="{ open: advOpen }">▸</span>
          高级选项（一般不用动：路径与响应字段已由协议模板内置）
        </button>
        <div v-show="advOpen">
          <div class="hint" style="margin-bottom: 8px">
            <b>接口路径覆盖</b>：仅当这家上游的接口路径与模板默认（{{ currentProto.defaultPath }}）不同时才填。
            <span v-if="w.protocol === 'OPENAI_ASYNC_TASK'">
              <b>响应字段</b>：任务号/状态/结果 URL 在响应里的位置，已按所选风格预填，仅在不一致时修改。</span>
          </div>
          <input v-model="w.submitPath" class="input" placeholder="提交路径（默认见模板）" />
          <template v-if="w.protocol === 'OPENAI_IMAGES_SYNC' || w.protocol === 'OPENAI_ASYNC_TASK'">
            <div style="display: flex; gap: 8px; margin-top: 8px; align-items: center">
              <XSelect v-model="w.refStyle" style="width: 280px" :options="refStyleOptions" />
              <span class="hint">带参考图时自动切图生图端点；纯文生图不受影响</span>
            </div>
            <input v-model="w.editsSubmitPath" class="input" style="margin-top: 8px"
                   placeholder="图生图端点（默认 /images/edits · 异步 /images/edits/async）" />
            <div v-if="w.protocol === 'OPENAI_IMAGES_SYNC'" style="display: flex; gap: 8px; margin-top: 8px; align-items: center">
              <XSelect v-model="w.respFormat" style="width: 280px" :options="respFormatOptions" />
              <span class="hint">业务方未显式指定时代为注入，显式传入优先</span>
            </div>
          </template>
          <template v-if="w.protocol === 'OPENAI_ASYNC_TASK'">
            <div style="display: flex; gap: 8px; margin-top: 8px">
              <XSelect v-model="w.flavor" style="width: 150px" :options="flavorOptions" @update:model-value="applyFlavorDefaults" />
              <input v-model="w.queryPath" class="input" placeholder="查询路径 /images/jobs/{id}" />
            </div>
            <div style="display: flex; gap: 8px; margin-top: 8px">
              <input v-model="w.idField" class="input" placeholder="任务号字段 id" />
              <input v-model="w.statusField" class="input" placeholder="状态字段 status" />
              <input v-model="w.urlField" class="input" placeholder="结果字段 data[*].url" />
            </div>
          </template>
        </div>

        <div style="display: flex; gap: 10px; margin-top: 16px">
          <button class="btn ghost" style="flex: 1" @click="step = 0">上一步</button>
          <button class="btn primary" style="flex: 2" :disabled="!w.name || !w.baseUrl || !w.apiKey"
                  @click="step = 2">下一步</button>
        </div>
      </div>

      <!-- Step 3: 试跑 -->
      <div v-if="step === 2">
        <div v-if="probeResult" class="alert" :class="probeResult.ok ? 'success' : 'error'">
          {{ probeResult.ok ? '✅ ' + probeResult.msg : '❌ ' + probeResult.msg }}
        </div>
        <div v-else class="hint" style="margin-bottom: 14px">
          试跑会真实调用一次上游的探活接口（不产生生图费用），验证接入点可达与 Key 有效。
        </div>
        <div style="display: flex; gap: 10px">
          <button class="btn ghost" style="flex: 1" @click="step = 1">上一步</button>
          <button class="btn ghost" style="flex: 1" :disabled="probing" @click="doProbe">
            <span v-if="probing" class="spin"></span>{{ probeResult ? '再试一次' : '试跑' }}
          </button>
          <button class="btn primary" style="flex: 1" :disabled="saving" @click="save">
            <span v-if="saving" class="spin"></span>保存{{ probeResult?.ok ? '并启用' : '' }}
          </button>
        </div>
      </div>
    </XDialog>

    <!-- 模型管理 -->
    <XDialog v-model="modelsVisible" :title="`模型清单 - ${current?.name || ''}`" width="640px">
      <div style="display: flex; gap: 8px; margin-bottom: 10px">
        <input v-model="manualModel" class="input" placeholder="人工录入上游模型名（Crun 用），如 openai/gpt-image-2-5" @keyup.enter="addModel" />
        <button class="btn primary" @click="addModel">录入</button>
      </div>
      <div style="max-height: 280px; overflow: auto">
        <table class="xtable">
          <thead><tr><th>模型名</th><th style="width: 80px">来源</th><th style="width: 70px"></th></tr></thead>
          <tbody>
            <tr v-for="row in models" :key="row.id">
              <td class="mono">{{ row.modelCode }}</td>
              <td class="hint">{{ row.source === 1 ? '同步' : '人工' }}</td>
              <td><button class="btn text dangertext sm" @click="delModel(row)">删除</button></td>
            </tr>
            <tr v-if="!models.length"><td colspan="3" class="hint" style="text-align: center">还没有模型</td></tr>
          </tbody>
        </table>
      </div>

      <hr class="divider" data-label="批量导入（每行一个，支持 Markdown 列表 / JSON 数组）">
      <textarea v-model="importText" class="input" rows="5"
                placeholder="openai/gpt-image-2-5&#10;google/nano-banana-2-1&#10;也可以直接粘 Markdown 列表或 JSON 数组"></textarea>
      <button class="btn primary" style="margin-top: 8px" :disabled="importing" @click="importModels">
        <span v-if="importing" class="spin"></span>批量导入
      </button>
    </XDialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { toast, confirm } from '../ui'
import XDialog from '../components/XDialog.vue'
import XSelect from '../components/XSelect.vue'
import api, { unwrap } from '../api'

const rows = ref([])
const wizardVisible = ref(false)
const step = ref(0)
const probeResult = ref(null)
const probing = ref(false)
const saving = ref(false)
const advOpen = ref(false)
const curlNotices = ref([])
const modelsVisible = ref(false)
const current = ref(null)
const models = ref([])
const manualModel = ref('')
const importText = ref('')
const importing = ref(false)

const protocols = [
  { value: 'OPENAI_IMAGES_SYNC', label: 'OpenAI 同步生图', desc: '适合 apiyi / laozhang / uuapi 的图片模型，一次请求直接返回图片', placeholder: 'https://api.apiyi.com/v1', baseUrlHint: '一般以 /v1 结尾，可从文档 curl 自动识别', defaultPath: '/images/generations', defaultQueryPath: '' },
  { value: 'OPENAI_ASYNC_TASK', label: 'OpenAI 异步任务', desc: '适合 nezhagate / uuapi 异步通道：提交拿任务号，网关自动轮询', placeholder: 'https://nezhagate.com/v1', baseUrlHint: '以 /v1 结尾；响应字段风格下一步可选', defaultPath: '/images/generations', defaultQueryPath: 'nezha=/images/jobs/{id} · uu=/images/tasks/{id}' },
  { value: 'CRUN_TASK', label: 'Crun 私有任务', desc: '适合 crun.ai：CreateTask/TaskInfo 私有协议', placeholder: 'https://api.crunai.com/api/v1', baseUrlHint: '必须以 /api/v1 结尾', defaultPath: '/client/job/CreateTask', defaultQueryPath: '/client/job/TaskInfo?task_id={id}' },
  { value: 'VOLCANO_ARK', label: '火山方舟 Seedance', desc: '视频协议，生图阶段暂未接入', placeholder: '', baseUrlHint: '', defaultPath: '', disabled: true }
]
const currentProto = computed(() => protocols.find(p => p.value === w.protocol) || protocols[0])

const w = reactive({
  id: null, protocol: '', curlText: '', queryCurlText: '', name: '', baseUrl: '', apiKey: '',
  maxConcurrency: 5, remark: '', submitPath: '', editsSubmitPath: '', flavor: 'nezha', refStyle: '', respFormat: '',
  queryPath: '', idField: '', statusField: '', urlField: '', editing: false
})
// 编辑时的配置底稿：保存按底稿合并回写，向导不认识的键（如 statusMap）不会被抹掉
let extraBase = {}
let pathBase = {}

const protoLabel = v => ({ OPENAI_IMAGES_SYNC: 'OpenAI 同步', OPENAI_ASYNC_TASK: 'OpenAI 异步', CRUN_TASK: 'Crun', VOLCANO_ARK: '火山方舟' }[v] || v)
const protoBadge = v => ({ OPENAI_IMAGES_SYNC: 'green', OPENAI_ASYNC_TASK: 'orange', CRUN_TASK: 'green', VOLCANO_ARK: 'gray' }[v] || 'gray')

const flavorOptions = [
  { label: '哪吒风格', value: 'nezha' },
  { label: 'UU 风格', value: 'uu' }
]
const refStyleOptions = [
  { label: '图生图参考图：默认（image 直传）', value: '' },
  { label: '图生图参考图：uuapi（自动切 edits 端点）', value: 'uuapi' }
]
const respFormatOptions = [
  { label: '响应格式：跟随上游（不干预）', value: '' },
  { label: '响应格式：默认回公网 URL', value: 'url' },
  { label: '响应格式：默认回 b64_json', value: 'b64_json' }
]

function statusText(row) {
  if (row.enabled !== 1) return '手动停用'
  return { 1: '正常', 2: '熔断中', 3: '半开探测', 4: '余额停用' }[row.status] || '未知'
}
function statusBadge(row) {
  if (row.enabled !== 1) return 'gray'
  return { 1: 'green', 2: 'red', 3: 'orange', 4: 'red' }[row.status] || 'gray'
}

// ---------------- curl 解析 ----------------
const curlParsed = computed(() => parseCurl(w.curlText))
const queryParsed = computed(() => parseCurl(w.queryCurlText))

function parseCurl(text) {
  if (!text || !text.trim()) return null
  const tokens = text.match(/"[^"]*"|'[^']*'|\S+/g) || []
  let url = null
  for (const raw of tokens) {
    const t = raw.replace(/^['"]|['"]$/g, '')
    if (/^https?:\/\//.test(t)) { url = t; break }
  }
  if (!url) return null
  try {
    const u = new URL(url)
    const m = u.pathname.match(/^(.*?\/v1)(\/|$)/)
    const base = m ? u.origin + m[1] : u.origin
    const path = m ? u.pathname.slice(m[1].length) : u.pathname
    return { base, path: path === '/' ? '' : path }
  } catch (e) {
    return null
  }
}

function pickProtocol(p) {
  if (p.disabled) return
  w.protocol = p.value
  applyFlavorDefaults()
  const parsed = parseCurl(w.curlText)
  if (parsed) w.baseUrl = parsed.base
  else if (!w.editing) w.baseUrl = p.placeholder
}

function applyCurlAndNext() {
  curlNotices.value = []
  const parsed = parseCurl(w.curlText)
  if (parsed) {
    // Base URL：从 curl 提取；与当前值不同时明确告知（修复静默替换域名，如 .net/.io 混用）
    if (parsed.base && parsed.base !== w.baseUrl) {
      curlNotices.value.push(`Base URL 已从 curl 识别并填充：${parsed.base}`)
      w.baseUrl = parsed.base
    }
    // 提交路径：与模板默认不同时自动采用并告知；/edits 是图生图端点，存 editsSubmitPath 不污染 submitPath
    const cp = normPath(parsed.path)
    const dp = normPath(currentProto.value.defaultPath)
    if (cp && dp && cp !== dp) {
      if (cp.includes('/edits')) {
        // 图生图端点：只管带参考图的请求，存 editsSubmitPath；文生图永远走模板默认
        w.editsSubmitPath = cp
        if (w.protocol === 'OPENAI_ASYNC_TASK' && cp.includes('/async') && w.flavor !== 'uu') {
          w.flavor = 'uu'
          applyFlavorDefaults()
          curlNotices.value.push('图生图路径含 /async，已自动切换为 UU 风格（查询字段已按 UU 预填）')
        }
        curlNotices.value.push(`检测到图生图端点 ${cp}：已存入「图生图端点」（仅带参考图时使用），文生图仍走模板默认（${dp}），请在下方接口确认里核对`)
      } else {
        w.submitPath = cp
        curlNotices.value.push(`提交路径与模板默认（${dp}）不同，已按 curl 采用：${cp}（注意：路径覆盖对该渠道所有请求生效）`)
        // 风格自动判断：贴的是 /async 路径就切 UU 风格，不再依赖手动发现高级选项
        if (w.protocol === 'OPENAI_ASYNC_TASK' && cp.includes('/async') && w.flavor !== 'uu') {
          w.flavor = 'uu'
          applyFlavorDefaults()
          curlNotices.value.push('提交路径含 /async，已自动切换为 UU 风格（响应字段已按 UU 预填）')
        }
        // UU 风格是推断（预选，可关）
        if (w.flavor === 'uu' && w.protocol === 'OPENAI_ASYNC_TASK' && !w.refStyle) {
          w.refStyle = 'uuapi'
          curlNotices.value.push('已按 UU 风格默认启用「图生图参考图：uuapi」，带参考图时自动切 /images/edits/async，可在高级选项关闭')
        }
      }
    }
  }
  const q = parseCurl(w.queryCurlText)
  if (q?.path) {
    // 查询路径：把文档示例里的真实任务号模板化为 {id}，防止示例值污染配置
    const templated = templateizeQuery(q.path)
    if (templated !== q.path) {
      curlNotices.value.push(`查询路径中的示例任务号已模板化为 {id}：${templated}`)
    }
    const sameAsDefault = [currentProto.value.defaultQueryPath || ''].some(
      d => normPath(String(d).split('?')[0]) === normPath(templated))
    if (!sameAsDefault && templated !== w.queryPath) {
      w.queryPath = templated
    }
  }
  step.value = 1
}

const normPath = p => (p ? '/' + String(p).replace(/^\/+|\/+$/g, '') : '')

// 查询路径最后一段长得像任务号（含数字的静态段）→ 替换为 {id}
function templateizeQuery(p) {
  const segs = String(p || '').split('/').filter(s => s !== '')
  if (segs.length < 2) return p
  const last = segs[segs.length - 1]
  if (last.includes('{') || !/[0-9]/.test(last)) return p
  segs[segs.length - 1] = '{id}'
  return '/' + segs.join('/')
}

function applyFlavorDefaults() {
  if (w.protocol !== 'OPENAI_ASYNC_TASK') return
  const uu = w.flavor === 'uu'
  w.submitPath = uu ? '/images/generations/async' : '/images/generations'
  w.queryPath = uu ? '/images/tasks/{id}' : '/images/jobs/{id}'
  w.idField = uu ? 'task_id' : 'id'
  w.statusField = 'status'
  w.urlField = 'data[*].url'
}

// ---------------- 向导动作 ----------------
function openWizard() {
  extraBase = {}
  pathBase = {}
  Object.assign(w, {
    id: null, protocol: '', curlText: '', queryCurlText: '', name: '', baseUrl: '', apiKey: '',
    maxConcurrency: 5, remark: '', submitPath: '', editsSubmitPath: '', flavor: 'nezha', refStyle: '', respFormat: '',
    queryPath: '', idField: '', statusField: '', urlField: '', editing: false
  })
  probeResult.value = null
  curlNotices.value = []
  advOpen.value = false
  step.value = 0
  wizardVisible.value = true
}

function openEdit(row) {
  pathBase = row.pathOverrides ? JSON.parse(row.pathOverrides) : {}
  extraBase = row.extraConfig ? JSON.parse(row.extraConfig) : {}
  Object.assign(w, {
    id: row.id, protocol: row.protocol, curlText: '', name: row.name, baseUrl: row.baseUrl, apiKey: '',
    maxConcurrency: row.maxConcurrency || 5, remark: row.remark || '', editing: true,
    submitPath: pathBase.submitPath || '', editsSubmitPath: pathBase.editsSubmitPath || '',
    queryPath: pathBase.queryPath || extraBase.queryPath || '',
    flavor: extraBase.flavor || 'nezha',
    refStyle: extraBase.refImageStyle || '', respFormat: extraBase.defaultResponseFormat || '',
    idField: extraBase.idField || '',
    statusField: extraBase.statusField || '', urlField: extraBase.urlField || ''
  })
  probeResult.value = null
  advOpen.value = true
  step.value = 1
  wizardVisible.value = true
}

function buildPayload() {
  const setKey = (obj, key, value) => { if (value) obj[key] = value; else delete obj[key] }
  const pathOverrides = { ...pathBase }
  setKey(pathOverrides, 'submitPath',
          w.submitPath && w.submitPath !== currentProto.value.defaultPath ? w.submitPath : '')
  // 图生图端点：仅带参考图的请求使用，无默认值概念，填了就存
  setKey(pathOverrides, 'editsSubmitPath', w.editsSubmitPath)
  setKey(pathOverrides, 'queryPath',
          w.queryPath && w.queryPath !== currentProto.value.defaultQueryPath ? w.queryPath : '')
  const extra = { ...extraBase }
  if (w.protocol === 'OPENAI_ASYNC_TASK') {
    extra.flavor = w.flavor
    setKey(extra, 'queryPath', w.queryPath)
    setKey(extra, 'idField', w.idField)
    setKey(extra, 'statusField', w.statusField)
    setKey(extra, 'urlField', w.urlField)
  } else {
    ;['flavor', 'queryPath', 'idField', 'statusField', 'urlField'].forEach(k => delete extra[k])
  }
  // 图生图参考图风格：两个 OpenAI 协议族通用（带参考图自动切 edits 系端点）
  setKey(extra, 'refImageStyle', w.refStyle)
  if (w.protocol === 'OPENAI_IMAGES_SYNC') {
    setKey(extra, 'defaultResponseFormat', w.respFormat)
  } else {
    delete extra.defaultResponseFormat
  }
  return {
    name: w.name,
    protocol: w.protocol,
    baseUrl: w.baseUrl,
    apiKey: w.apiKey || null,
    pathOverrides: Object.keys(pathOverrides).length ? pathOverrides : null,
    extraConfig: Object.keys(extra).length ? extra : null,
    maxConcurrency: w.maxConcurrency,
    remark: w.remark
  }
}

async function doProbe() {
  probing.value = true
  try {
    const data = await unwrap(api.post('/admin/channels/probe',
      { protocol: w.protocol, baseUrl: w.baseUrl, apiKey: w.apiKey }))
    const ok = data.result.includes('连通正常')
    probeResult.value = { ok, msg: data.result }
  } catch (e) {
    probeResult.value = { ok: false, msg: e?.msg || '试跑失败' }
  } finally {
    probing.value = false
  }
}

async function save() {
  if (!probeResult.value?.ok) {
    const ok = await confirm({
      title: '未试跑通过',
      message: '还没试跑通过，确定直接保存？（可以先保存，渠道启用前建议试跑）'
    })
    if (!ok) return
  }
  saving.value = true
  try {
    if (w.id) await unwrap(api.put(`/admin/channels/${w.id}`, buildPayload()))
    else await unwrap(api.post('/admin/channels', buildPayload()))
    toast('已保存')
    wizardVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

// ---------------- 列表操作 ----------------
async function testRun(row) {
  const data = await unwrap(api.post(`/admin/channels/${row.id}/test-run`))
  data.result.includes('连通正常') ? toast(data.result) : toast(data.result, 'warn')
}

async function syncModels(row) {
  const data = await unwrap(api.post(`/admin/channels/${row.id}/sync-models`))
  toast(`同步完成，共 ${data.count} 个模型`)
}

async function enable(row) {
  await unwrap(api.post(`/admin/channels/${row.id}/enable`))
  toast('已启用')
  await load()
}

async function disable(row) {
  const ok = await confirm({
    title: '停用渠道',
    message: `确认停用「${row.name}」？`,
    danger: true, confirmText: '停用'
  })
  if (!ok) return
  await unwrap(api.post(`/admin/channels/${row.id}/disable`, { reason: '手动停用' }))
  await load()
}

async function manageModels(row) {
  current.value = row
  manualModel.value = ''
  models.value = await unwrap(api.get(`/admin/channels/${row.id}/models`))
  modelsVisible.value = true
}

async function addModel() {
  if (!manualModel.value.trim()) return
  await unwrap(api.post(`/admin/channels/${current.value.id}/models`, { modelCode: manualModel.value.trim() }))
  manualModel.value = ''
  models.value = await unwrap(api.get(`/admin/channels/${current.value.id}/models`))
}

async function importModels() {
  if (!importText.value.trim()) return
  importing.value = true
  try {
    const data = await unwrap(api.post(`/admin/channels/${current.value.id}/models/import`,
      { content: importText.value }))
    toast(`导入完成：解析 ${data.total} 个，新增/启用 ${data.added} 个`)
    importText.value = ''
    models.value = await unwrap(api.get(`/admin/channels/${current.value.id}/models`))
  } finally {
    importing.value = false
  }
}

async function delModel(row) {
  await unwrap(api.delete(`/admin/channels/${current.value.id}/models/${row.id}`))
  models.value = await unwrap(api.get(`/admin/channels/${current.value.id}/models`))
}

async function load() {
  rows.value = await unwrap(api.get('/admin/channels'))
}

onMounted(load)
</script>
