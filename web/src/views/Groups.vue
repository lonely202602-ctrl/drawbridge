<template>
  <div>
    <!-- 分组卡片墙（双击打开编辑器） -->
    <div class="card-wall">
      <div v-for="(g, gi) in groups" :key="g.id" class="db-card group-card zoom-in"
           :style="{ animationDelay: (gi * 50) + 'ms' }"
           @dblclick="openEditor(g)">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
          <b style="font-size: 17px">{{ g.groupCode }}</b>
          <div style="display: flex; align-items: center; gap: 6px">
            <XDropdown>
              <template #trigger>
                <button class="btn ghost sm" style="padding: 4px 10px" title="分组操作">···</button>
              </template>
              <div class="menu-item" @click="openEditor(g)">管理成员</div>
              <div class="menu-item" @click="editForm = { id: g.id, groupCode: g.groupCode, displayName: g.displayName, remark: g.remark }; createVisible = true">编辑信息</div>
              <hr>
              <div v-if="g.enabled === 1" class="menu-item" @click="groupMenu('disable', g)">停用分组</div>
              <div v-else class="menu-item" @click="groupMenu('enable', g)">启用分组</div>
              <div class="menu-item del" @click="groupMenu('remove', g)">删除分组</div>
            </XDropdown>
            <span v-if="g.enabled !== 1" class="badge gray" style="margin-right: 6px">已停用</span>
          </div>
        </div>

        <div class="chip-row" style="margin-bottom: 12px">
          <span v-for="s in strategies" :key="s.value" class="chip"
                :class="{ active: g.strategy === s.value }" @click="setStrategy(g, s)">
            {{ s.label }}
          </span>
        </div>

        <TransitionGroup name="member" tag="div" class="member-list">
          <div v-for="(m, i) in g.members" :key="m.id" class="member-row"
               :style="m.enabled !== 1 ? 'opacity:.45' : ''">
            <span class="no">{{ i + 1 }}</span>
            <div class="m-main">
              <b :title="m.upstreamModel">{{ m.upstreamModel }}</b>
              <small>{{ m.channelName }}<span v-if="m.channelStatus === 0 || m.channelStatus === 4" style="color: var(--danger)"> · 渠道停用</span><span v-if="g.strategy === 'WEIGHTED'"> · 权重 {{ m.weight }}</span></small>
            </div>
            <div class="m-ops">
              <button class="btn text sm" :disabled="i === 0" @click="moveMember(g, g.members, i, -1)">↑</button>
              <button class="btn text sm" :disabled="i === g.members.length - 1" @click="moveMember(g, g.members, i, 1)">↓</button>
              <button class="btn text sm" @click="toggleMember(g, m)">{{ m.enabled === 1 ? '停' : '启' }}</button>
              <button class="btn text dangertext sm" @click="removeMember(g, m)">✕</button>
            </div>
          </div>
        </TransitionGroup>
        <div v-if="!g.members.length" class="hint" style="text-align: center; padding: 18px 0">
          还没有成员——双击卡片去添加
        </div>

        <button class="btn ghost block" style="margin-top: 6px" @click="openEditor(g)">＋ 添加成员</button>
        <div class="hint" style="text-align: center; margin-top: 6px">双击卡片打开成员编辑器（可手拖排序）</div>
      </div>

      <div class="db-card zoom-in" style="display: flex; align-items: center; justify-content: center; min-height: 120px;
           cursor: pointer; color: var(--accent); flex-direction: column; gap: 6px; border: 2px dashed #c2d6c8"
           @click="openCreate">
        <div style="font-size: 26px">＋</div>
        <div>新建分组</div>
      </div>
    </div>

    <!-- 成员编辑器（参考 octopus：左可添加 / 右已选，手拖排序） -->
    <XDialog v-model="editorVisible" :width="'1060px'" :title="`编辑分组 - ${editor?.groupCode || ''}`">
      <template v-if="editor">
        <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap">
          <span style="font-weight: 600">路由策略</span>
          <div class="chip-row">
            <span v-for="s in strategies" :key="s.value" class="chip"
                  :class="{ active: editor.strategy === s.value }" @click="setStrategy(editor, s)">
              {{ s.label }}
            </span>
          </div>
          <span class="hint" style="margin-left: auto">
            {{ strategyHint(editor.strategy) }}
          </span>
        </div>

        <div class="editor-grid">
          <!-- 左：可添加模型 -->
          <div class="editor-panel">
            <div class="panel-head">
              <span>添加模型</span>
              <input v-model="search" class="input" placeholder="搜索模型" style="width: 150px; padding: 6px 12px" />
            </div>
            <div class="panel-body">
              <div v-for="ch in editorModels" :key="ch.channelId" style="margin-bottom: 8px">
                <div class="ch-row" @click="ch.open = !ch.open">
                  <span class="arrow" :style="ch.open ? 'transform: rotate(90deg)' : ''">▸</span>
                  <b>{{ ch.name }}</b>
                  <span class="hint" style="margin-left: 6px">{{ addedCount(ch) }}/{{ ch.models.length }}</span>
                </div>
                <div v-show="ch.open">
                  <div v-for="m in filterModels(ch)" :key="m.modelCode" class="model-item"
                       :class="{ added: m.added }"
                       @click="!m.added && quickAdd(ch, m)">
                    <span style="word-break: break-all">{{ m.modelCode }}</span>
                    <span class="hint" v-if="m.added">已添加</span>
                  </div>
                  <div v-if="!filterModels(ch).length" class="hint" style="padding: 4px 12px">无匹配模型</div>
                </div>
              </div>
              <div v-if="!editorModels.length" class="hint" style="text-align: center; padding: 20px">
                还没有渠道模型清单——先去渠道页同步/录入模型
              </div>
            </div>
          </div>

          <!-- 右：已选成员 -->
          <div class="editor-panel">
            <div class="panel-head">
              <span>已选成员（{{ editor.members.length }}）</span>
              <button class="btn text dangertext sm" :disabled="!editor.members.length" @click="clearMembers">清空</button>
            </div>
            <div class="panel-body">
              <TransitionGroup name="member">
                <div v-for="(m, i) in editor.members" :key="m.id" class="member-row"
                     :style="m.enabled !== 1 ? 'opacity:.45' : ''"
                     draggable="true"
                     :class="{ dragging: dragIndex === i }"
                     @dragstart="dragIndex = i"
                     @dragover.prevent
                     @drop.prevent="onEditorDrop(i)"
                     @dragend="dragIndex = -1">
                  <span class="no">{{ i + 1 }}</span>
                  <span class="drag-handle" title="拖动排序">⠿</span>
                  <div class="m-main">
                    <b style="white-space: normal; word-break: break-all">{{ m.upstreamModel }}</b>
                    <small>{{ m.channelName }}<span v-if="m.channelStatus === 0 || m.channelStatus === 4" style="color: var(--danger)"> · 渠道停用</span><span v-if="editor.strategy === 'WEIGHTED'"> · 权重 {{ m.weight }}</span></small>
                  </div>
                  <div class="m-ops">
                    <button class="btn text sm" @click="toggleMember(editor, m)">{{ m.enabled === 1 ? '停' : '启' }}</button>
                    <button class="btn text dangertext sm" @click="removeMember(editor, m)">✕</button>
                  </div>
                </div>
              </TransitionGroup>
              <div v-if="!editor.members.length" class="hint" style="text-align: center; padding: 24px 0">
                从左侧点击模型添加；拖动右侧成员可排序
              </div>
            </div>
          </div>
        </div>

        <div style="text-align: center; margin-top: 16px">
          <button class="btn ghost" style="width: 220px" @click="editorVisible = false">完成（改动已即时生效）</button>
        </div>
      </template>
    </XDialog>

    <!-- 新建/编辑分组 -->
    <XDialog v-model="createVisible" :title="editForm.id ? '编辑分组' : '新建分组'" width="440px">
      <div class="field">
        <label>分组编码</label>
        <input v-model="editForm.groupCode" class="input" placeholder="业务方调用的 model 名，如 image2.5" />
        <div v-if="editForm.id" class="form-hint">
          修改编码后，业务方需改用新 model 名调用（已限分组的令牌会自动同步）
        </div>
      </div>
      <div class="field"><label>显示名</label>
        <input v-model="editForm.displayName" class="input" placeholder="可选" /></div>
      <div class="field"><label>备注</label>
        <input v-model="editForm.remark" class="input" /></div>
      <template #footer>
        <button class="btn primary block" @click="saveGroup">保存</button>
      </template>
    </XDialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { toast, confirm } from '../ui'
import XDialog from '../components/XDialog.vue'
import XDropdown from '../components/XDropdown.vue'
import api, { unwrap } from '../api'

const strategies = [
  { value: 'FAILOVER', label: '故障转移' },
  { value: 'POLLING', label: '轮询' },
  { value: 'RANDOM', label: '随机' },
  { value: 'WEIGHTED', label: '加权分配' },
  { value: 'IDLE_FIRST', label: '空闲优先' }
]
const groups = ref([])
const channels = ref([])
const createVisible = ref(false)
const editForm = ref({ id: null, groupCode: '', displayName: '', remark: '' })

const editorVisible = ref(false)
const editor = ref(null)
const editorModels = ref([])   // [{channelId, name, open, models:[{modelCode, added}]}]
const search = ref('')
const dragIndex = ref(-1)

const strategyLabel = v => strategies.find(s => s.value === v)?.label || '按成员顺序故障转移'
const strategyHint = v => ({
  FAILOVER: '按序号顺序：1 号优先，饱和时排队等待（不跳槽），失败才轮到下一成员',
  POLLING: '成员轮流接单，均匀分配；饱和的成员自动跳过',
  RANDOM: '每次随机挑一个成员；饱和的成员自动跳过',
  WEIGHTED: '按权重比例分配流量；饱和的成员自动跳过',
  IDLE_FIRST: '按序号顺序取第一个有空闲并发的成员；全满时排队等待'
}[v] || '')

async function load() {
  const list = await unwrap(api.get('/admin/groups'))
  channels.value = await unwrap(api.get('/admin/channels'))
  groups.value = []
  for (const g of list) {
    const detail = await unwrap(api.get(`/admin/groups/${g.id}`))
    groups.value.push({ ...g, displayName: detail.displayName, remark: detail.remark, members: detail.members })
  }
}

// ---------------- 停用 / 启用 / 删除 ----------------
async function groupMenu(cmd, g) {
  if (cmd === 'disable') {
    const ok = await confirm({
      title: '停用分组',
      message: `停用分组「${g.groupCode}」后业务方将无法调用该模型，确认？`,
      danger: false
    })
    if (!ok) return
    await unwrap(api.post(`/admin/groups/${g.id}/disable`))
    toast(`分组「${g.groupCode}」已停用`)
  } else if (cmd === 'enable') {
    await unwrap(api.post(`/admin/groups/${g.id}/enable`))
    toast(`分组「${g.groupCode}」已启用`)
  } else if (cmd === 'remove') {
    const ok = await confirm({
      title: '删除分组',
      message: `删除分组「${g.groupCode}」会一并移除它的成员条目，且不可恢复（历史任务不受影响；有在途任务时会被拒绝）。确认删除？`,
      danger: true, confirmText: '删除'
    })
    if (!ok) return
    await unwrap(api.delete(`/admin/groups/${g.id}`))
    toast(`分组「${g.groupCode}」已删除`)
  }
  await load()
}

// ---------------- 编辑器 ----------------
async function openEditor(g) {
  editor.value = g
  search.value = ''
  editorVisible.value = true
  const withModels = channels.value.filter(c => c.enabled === 1)
  const results = await Promise.all(withModels.map(async c => ({
    channelId: c.id,
    name: c.name,
    open: true,
    models: (await unwrap(api.get(`/admin/channels/${c.id}/models`))).map(m => ({
      modelCode: m.modelCode,
      get added() {
        return !!editor.value?.members.some(mem =>
          mem.channelId === c.id && mem.upstreamModel === m.modelCode)
      }
    }))
  })))
  if (editor.value === g) {
    editorModels.value = results
  }
}

function addedCount(ch) {
  return ch.models.filter(m => m.added).length
}

function filterModels(ch) {
  const q = search.value.trim().toLowerCase()
  return ch.models.filter(m => !q || m.modelCode.toLowerCase().includes(q))
}

async function quickAdd(ch, m) {
  const g = editor.value
  const resp = await unwrap(api.post(`/admin/groups/${g.id}/members`, {
    channelId: ch.channelId,
    upstreamModel: m.modelCode,
    weight: 1,
    sortOrder: g.members.length,
    enabled: 1
  }))
  const newId = typeof resp === 'object' && resp !== null ? resp.id : resp
  if (newId == null) { await load(); return }
  g.members.push({
    id: newId,
    channelId: ch.channelId,
    channelName: ch.name,
    upstreamModel: m.modelCode,
    weight: 1,
    sortOrder: g.members.length,
    enabled: 1
  })
  toast(`已添加 ${m.modelCode}`)
}

async function removeMember(g, m) {
  await unwrap(api.delete(`/admin/groups/${g.id}/members/${m.id}`))
  const arr = g.members
  arr.splice(arr.findIndex(x => x.id === m.id), 1)
  g.members.forEach((x, i) => { x.sortOrder = i })
}

async function toggleMember(g, m) {
  await unwrap(api.put(`/admin/groups/${g.id}/members/${m.id}`, {
    upstreamModel: m.upstreamModel, weight: m.weight, sortOrder: m.sortOrder,
    enabled: m.enabled === 1 ? 0 : 1
  }))
  m.enabled = m.enabled === 1 ? 0 : 1
}

async function clearMembers() {
  const g = editor.value
  const ok = await confirm({
    title: '清空成员',
    message: `清空分组「${g.groupCode}」的全部成员？业务方将无法调用。`,
    danger: true, confirmText: '清空'
  })
  if (!ok) return
  for (const m of [...g.members]) {
    await unwrap(api.delete(`/admin/groups/${g.id}/members/${m.id}`))
  }
  g.members = []
  toast('已清空')
}

// ---------------- 排序：手拖 + 按钮（本地即时更新 + 静默持久化） ----------------
function onEditorDrop(targetIndex) {
  const g = editor.value
  const from = dragIndex.value
  dragIndex.value = -1
  if (from < 0 || from === targetIndex) return
  const arr = g.members
  const [moved] = arr.splice(from, 1)
  arr.splice(targetIndex, 0, moved)
  persistSort(g)
}

async function moveMember(g, members, index, dir) {
  const arr = [...members]
  const [moved] = arr.splice(index, 1)
  arr.splice(index + dir, 0, moved)
  members.splice(0, members.length, ...arr)
  await persistSort(g)
}

/** 本地数组已重排，只把变化的 sortOrder 静默写库（不整页刷新，保住过渡动画）。 */
async function persistSort(g) {
  g.members.forEach((m, i) => { m.sortOrder = i })
  for (const m of g.members) {
    await unwrap(api.put(`/admin/groups/${g.id}/members/${m.id}`, {
      upstreamModel: m.upstreamModel, weight: m.weight, sortOrder: m.sortOrder, enabled: m.enabled
    }))
  }
}

async function setStrategy(g, s) {
  if (g.strategy === s.value) return
  await unwrap(api.put(`/admin/groups/${g.id}`, {
    groupCode: g.groupCode, displayName: g.displayName, strategy: s.value, remark: g.remark
  }))
  g.strategy = s.value
  toast(`「${g.groupCode}」已切换为${s.label}`)
}

// ---------------- 新建/编辑分组 ----------------
/** 新建必须重置表单：编辑过某个分组后表单里还留着它的 id，带着 id 保存会走 PUT 改掉那个分组 */
function openCreate() {
  editForm.value = { id: null, groupCode: '', displayName: '', remark: '' }
  createVisible.value = true
}

async function saveGroup() {
  if (!editForm.value.groupCode.trim()) {
    toast('分组编码不能为空', 'warn')
    return
  }
  if (editForm.value.id) {
    await unwrap(api.put(`/admin/groups/${editForm.value.id}`, {
      groupCode: editForm.value.groupCode, displayName: editForm.value.displayName,
      strategy: groups.value.find(x => x.id === editForm.value.id)?.strategy || 'FAILOVER',
      remark: editForm.value.remark
    }))
  } else {
    await unwrap(api.post('/admin/groups', {
      groupCode: editForm.value.groupCode.trim(), displayName: editForm.value.displayName,
      strategy: 'FAILOVER', remark: editForm.value.remark
    }))
  }
  createVisible.value = false
  toast('已保存')
  await load()
}

onMounted(load)
</script>

<style scoped>
.group-card { cursor: default; display: flex; flex-direction: column; }

/* 成员区限高：卡片不随成员数无限长高，超出内部滚动 */
.member-list {
  flex: 1 1 auto;
  min-height: 0;
  max-height: 300px;
  overflow-y: auto;
}

/* 编辑器面板 */
.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.editor-panel {
  border: 1px solid var(--line); border-radius: 14px; background: #fcfbf8;
  display: flex; flex-direction: column; height: 54vh;
}
.panel-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px; border-bottom: 1px solid var(--line); font-weight: 600; font-size: 13px;
}
.panel-body { flex: 1; overflow: auto; padding: 10px 12px; }
.ch-row {
  display: flex; align-items: center; padding: 6px 8px; cursor: pointer;
  border-radius: 8px; font-size: 13px;
}
.ch-row:hover { background: #f4f3ec; }
.ch-row .arrow { display: inline-block; transition: transform 0.2s; color: var(--ink2); font-size: 11px; width: 14px; }
.model-item {
  display: flex; justify-content: space-between; gap: 8px; align-items: center;
  padding: 6px 10px 6px 30px; border-radius: 8px; cursor: pointer; font-size: 12.5px;
}
.model-item:hover { background: var(--accent-wash); }
.model-item.added { color: var(--ink2); cursor: not-allowed; }
.model-item.added:hover { background: none; }

/* 列表变动动画 */
.member-move { transition: transform 0.3s cubic-bezier(0.22, 0.61, 0.36, 1); }
.member-enter-active { transition: all 0.22s ease; }
.member-leave-active { transition: all 0.18s ease; position: absolute; width: 100%; }
.member-enter-from { opacity: 0; transform: translateY(-8px); }
.member-leave-to { opacity: 0; transform: translateX(12px); }

.zoom-in { animation: fadeUp 0.3s ease both; }
@keyframes fadeUp {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
}

/* 危险操作菜单项（删除分组） */
.menu-item.del { color: var(--danger); }
.menu-item.del:hover { background: var(--danger-wash); }
</style>
