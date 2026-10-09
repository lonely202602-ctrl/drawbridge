<template>
  <div>
    <!-- 分组卡片墙（双击打开编辑器） -->
    <div class="card-wall">
      <div v-for="(g, gi) in groups" :key="g.id" class="db-card group-card zoom-in"
           :style="{ animationDelay: (gi * 50) + 'ms' }"
           @dblclick="openEditor(g)">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
          <b style="font-size: 17px">{{ g.groupCode }}</b>
          <div>
            <el-tag v-if="g.enabled !== 1" type="info" size="small" style="margin-right: 6px">已停用</el-tag>
            <el-dropdown trigger="click" @command="cmd => groupMenu(cmd, g)">
              <el-button link>···</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="editor">管理成员</el-dropdown-item>
                  <el-dropdown-item command="edit">编辑信息</el-dropdown-item>
                  <el-dropdown-item command="toggle" divided>{{ g.enabled === 1 ? '停用分组' : '启用分组' }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
              <small>{{ m.channelName }}<span v-if="m.channelStatus === 0 || m.channelStatus === 4" style="color: #b4523f"> · 渠道停用</span><span v-if="g.strategy === 'WEIGHTED'"> · 权重 {{ m.weight }}</span></small>
            </div>
            <div class="m-ops">
              <el-button link size="small" :disabled="i === 0" @click="moveMember(g, g.members, i, -1)">↑</el-button>
              <el-button link size="small" :disabled="i === g.members.length - 1" @click="moveMember(g, g.members, i, 1)">↓</el-button>
              <el-button link size="small" @click="toggleMember(g, m)">{{ m.enabled === 1 ? '停' : '启' }}</el-button>
              <el-button link size="small" type="danger" @click="removeMember(g, m)">✕</el-button>
            </div>
          </div>
        </TransitionGroup>
        <div v-if="!g.members.length" class="hint" style="text-align: center; padding: 18px 0">
          还没有成员——双击卡片去添加
        </div>

        <el-button round style="width: 100%; margin-top: 6px" @click="openEditor(g)">＋ 添加成员</el-button>
        <div class="hint" style="text-align: center; margin-top: 6px">双击卡片打开成员编辑器（可手拖排序）</div>
      </div>

      <div class="db-card zoom-in" style="display: flex; align-items: center; justify-content: center; min-height: 120px;
           cursor: pointer; color: #4a7c59; flex-direction: column; gap: 6px; border: 2px dashed #c2d6c8"
           @click="createVisible = true">
        <div style="font-size: 26px">＋</div>
        <div>新建分组</div>
      </div>
    </div>

    <!-- 成员编辑器（参考 octopus：左可添加 / 右已选，手拖排序） -->
    <el-dialog v-model="editorVisible" width="1060px" top="5vh" destroy-on-close
               :title="`编辑分组 - ${editor?.groupCode}`">
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
              <el-input v-model="search" size="small" placeholder="搜索模型" clearable style="width: 150px" />
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
              <el-button link type="danger" :disabled="!editor.members.length" @click="clearMembers">清空</el-button>
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
                    <small>{{ m.channelName }}<span v-if="m.channelStatus === 0 || m.channelStatus === 4" style="color: #b4523f"> · 渠道停用</span><span v-if="editor.strategy === 'WEIGHTED'"> · 权重 {{ m.weight }}</span></small>
                  </div>
                  <div class="m-ops">
                    <el-button link size="small" @click="toggleMember(editor, m)">{{ m.enabled === 1 ? '停' : '启' }}</el-button>
                    <el-button link size="small" type="danger" @click="removeMember(editor, m)">✕</el-button>
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
          <el-button round style="width: 200px" @click="editorVisible = false">完成（改动已即时生效）</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 新建/编辑分组 -->
    <el-dialog v-model="createVisible" :title="editForm.id ? '编辑分组' : '新建分组'" width="440px">
      <el-form label-width="100px">
        <el-form-item label="分组编码">
          <el-input v-model="editForm.groupCode" placeholder="业务方调用的 model 名，如 image2.5" />
          <div v-if="editForm.id" class="form-hint" style="width: 100%; font-size: 12px; color: #999; line-height: 1.6; margin-top: 4px">
            修改编码后，业务方需改用新 model 名调用（已限分组的令牌会自动同步）
          </div>
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="editForm.displayName" placeholder="可选" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="editForm.remark" /></el-form-item>
        <el-button type="primary" round style="width: 100%" @click="saveGroup">保存</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { unwrap } from '../api'

const strategies = [
  { value: 'FAILOVER', label: '故障转移' },
  { value: 'POLLING', label: '轮询' },
  { value: 'RANDOM', label: '随机' },
  { value: 'WEIGHTED', label: '加权分配' }
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

const protoLabel = v => ({ OPENAI_IMAGES_SYNC: 'OpenAI 同步', OPENAI_ASYNC_TASK: 'OpenAI 异步', CRUN_TASK: 'Crun', VOLCANO_ARK: '火山方舟' }[v] || v)
const strategyLabel = v => strategies.find(s => s.value === v)?.label || '按成员顺序故障转移'
const strategyHint = v => ({
  FAILOVER: '按序号顺序：1 号优先，失败才轮到下一成员',
  POLLING: '成员轮流接单，均匀分配',
  RANDOM: '每次随机挑一个成员',
  WEIGHTED: '按权重比例分配流量'
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
  ElMessage.success(`已添加 ${m.modelCode}`)
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
  await ElMessageBox.confirm(`清空分组「${g.groupCode}」的全部成员？业务方将无法调用。`, '提示', { type: 'warning' })
  for (const m of [...g.members]) {
    await unwrap(api.delete(`/admin/groups/${g.id}/members/${m.id}`))
  }
  g.members = []
  ElMessage.success('已清空')
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
  ElMessage.success(`「${g.groupCode}」已切换为${s.label}`)
}

async function saveGroup() {
  if (!editForm.value.groupCode.trim()) {
    ElMessage.warning('分组编码不能为空')
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
  ElMessage.success('已保存')
  await load()
}

async function groupMenu(cmd, g) {
  if (cmd === 'editor') openEditor(g)
  else if (cmd === 'edit') {
    editForm.value = { id: g.id, groupCode: g.groupCode, displayName: g.displayName, remark: g.remark }
    createVisible.value = true
  } else if (cmd === 'toggle') {
    if (g.enabled === 1) {
      await ElMessageBox.confirm(`停用分组「${g.groupCode}」后业务方将无法调用该模型，确认？`, '提示', { type: 'warning' })
    }
    await unwrap(api.delete(`/admin/groups/${g.id}`))
    await load()
  }
}

onMounted(load)
</script>

<style scoped>
.group-card { cursor: default; display: flex; flex-direction: column; }

/* 成员区限高：卡片不随成员数无限长高，超出内部滚动 */
.member-list {
  flex: 1 1 auto;
  min-height: 0;
  max-height: 236px;
  overflow-y: auto;
  padding-right: 2px;
}
.member-list::-webkit-scrollbar { width: 4px; }
.member-list::-webkit-scrollbar-thumb { background: #ddd9cc; border-radius: 4px; }
.member-list::-webkit-scrollbar-track { background: transparent; }

/* 编辑器双栏 */
.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.editor-panel {
  background: #faf9f5;
  border: 1px solid #eeede6;
  border-radius: 14px;
  overflow: hidden;
}
.panel-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px;
  font-weight: 600;
  border-bottom: 1px solid #eeede6;
  background: #fff;
}
.panel-body { height: 54vh; overflow: auto; padding: 10px 12px; position: relative; }
/* 离场动画的行绝对定位时，固定在本面板内（不飞出弹窗） */
.panel-body > div { position: relative; }
.ch-row {
  display: flex; align-items: center;
  padding: 8px 10px; border-radius: 10px;
  cursor: pointer; user-select: none;
}
.ch-row:hover { background: #f1efe8; }
.ch-row .arrow {
  display: inline-block; transition: transform 0.2s ease;
  margin-right: 6px; color: #9a9a90;
}
.model-item {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  padding: 8px 12px; margin: 2px 0 2px 18px;
  border-radius: 10px; cursor: pointer;
  transition: background 0.15s ease;
}
.model-item:hover { background: #ecf2ee; }
.model-item.added { opacity: 0.45; cursor: not-allowed; }
.model-item.added:hover { background: transparent; }

/* 成员行：弹窗窄面板内允许换行，卡片墙上单行省略（全局 style.css 的 nowrap+ellipsis） */
.member-row { cursor: grab; }
.member-row:active { cursor: grabbing; }
.member-row.dragging { opacity: 0.4; border-style: dashed; }
.drag-handle { color: #c0c0b6; cursor: grab; font-size: 13px; letter-spacing: 1px; }
.editor-grid .member-row .m-main b { white-space: normal; word-break: break-all; line-height: 1.4; }

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
</style>
