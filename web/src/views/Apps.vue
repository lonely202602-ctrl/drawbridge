<template>
  <div>
    <div class="stat-strip">
      <div class="stat-cell"><div><div class="num">{{ tokens.length }}</div><div class="lbl">令牌总数</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ tokens.filter(t => t.status === 1).length }}</div><div class="lbl">启用中</div></div></div>
      <div style="margin-left: auto; align-self: center">
        <button class="btn primary" @click="createVisible = true">＋ 发令牌</button>
      </div>
    </div>

    <div class="card-wall">
      <div v-for="t in tokens" :key="t.id" class="db-card">
        <div style="display: flex; justify-content: space-between; align-items: center">
          <b style="font-size: 16px">{{ t.name }}</b>
          <span class="badge" :class="t.status === 1 ? 'green' : 'gray'">{{ t.status === 1 ? '启用' : '禁用' }}</span>
        </div>
        <div class="hint" style="margin: 8px 0">
          SK 前缀 <code>{{ t.prefix }}</code> ·
          授权分组 <span :style="t.allowedGroups && !t.allowedGroups.length ? 'color: var(--danger); font-weight: 600' : ''">{{ groupsText(t.allowedGroups) }}</span>
          <span v-if="t.expiresAt"> · 过期于 {{ t.expiresAt }}</span>
        </div>
        <div style="display: flex; gap: 6px; flex-wrap: wrap">
          <button class="btn primary sm" @click="revealSk(t)">查看 SK</button>
          <button class="btn ghost sm" @click="openEditGroups(t)">编辑分组</button>
          <button class="btn ghost sm" @click="toggleToken(t)">{{ t.status === 1 ? '禁用' : '启用' }}</button>
          <button class="btn danger sm" @click="delToken(t)">删除</button>
        </div>
      </div>
      <div v-if="!tokens.length" class="db-card" style="text-align: center; color: var(--ink2); padding: 40px">
        还没有令牌，点右上角「发令牌」
      </div>
    </div>

    <!-- 发令牌 -->
    <XDialog v-model="createVisible" title="发令牌" width="440px">
      <div class="field"><label>令牌名</label>
        <input v-model="form.name" class="input" placeholder="给谁用的，如 banana-mall" @keyup.enter="save" /></div>
      <div class="field"><label>授权分组</label>
        <XSelect v-model="form.allowedGroups" multiple block :options="groupOptions"
                 placeholder="不选 = 可调用全部分组" /></div>
      <template #footer>
        <button class="btn primary block" @click="save">生成 SK</button>
      </template>
    </XDialog>

    <!-- SK 查看（创建后 / 随时查看共用） -->
    <XDialog v-model="skVisible" :title="skTitle" width="520px">
      <textarea class="input" :value="newSk" readonly rows="2" style="font-family: var(--mono)"></textarea>
      <template #footer>
        <button class="btn primary" @click="copySk">复制</button>
      </template>
    </XDialog>

    <!-- 编辑授权分组（扩宽 / 缩减） -->
    <XDialog v-model="editVisible" :title="`授权分组 - ${editTarget?.name || ''}`" width="460px">
      <template v-if="editTarget">
        <div v-if="editTarget.allowedGroups && !editTarget.allowedGroups.length" class="alert warning" style="margin-bottom: 12px">
          该令牌当前没有任何授权分组（原分组已被删除）。保存时若不选择分组，将恢复为「全部」。
        </div>
        <div class="field">
          <label>授权分组</label>
          <XSelect v-model="editGroups" multiple block :options="groupOptions"
                   placeholder="不选 = 可调用全部分组" />
          <div class="form-hint">SK 不变，业务方无感知；缩减后新任务立即无法调用被移除的分组</div>
        </div>
      </template>
      <template #footer>
        <button class="btn primary block" @click="saveGroups">保存</button>
      </template>
    </XDialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { toast, confirm } from '../ui'
import XDialog from '../components/XDialog.vue'
import XSelect from '../components/XSelect.vue'
import api, { unwrap } from '../api'

const tokens = ref([])
const groups = ref([])
const createVisible = ref(false)
const skVisible = ref(false)
const skTitle = ref('SK')
const newSk = ref('')
const form = ref({ name: '', allowedGroups: [] })
const editVisible = ref(false)
const editTarget = ref(null)
const editGroups = ref([])

const groupOptions = computed(() => groups.value.map(g => ({ label: g.groupCode, value: g.groupCode })))

/** null=全部；[]=无授权分组（曾限定的分组被删除）。两者必须区分，否则零权限被显示成"全部" */
function groupsText(g) {
  if (g === null || g === undefined) return '全部'
  if (!g.length) return '无授权分组（分组已删除，请重新授权）'
  return g.join('、')
}

async function load() {
  tokens.value = await unwrap(api.get('/admin/tokens'))
  groups.value = await unwrap(api.get('/admin/groups'))
}

async function save() {
  if (!form.value.name.trim()) {
    toast('令牌名不能为空', 'warn')
    return
  }
  const data = await unwrap(api.post('/admin/tokens', form.value))
  newSk.value = data.sk
  skTitle.value = '令牌已创建，SK 加密存储、可随时查看'
  createVisible.value = false
  skVisible.value = true
  form.value = { name: '', allowedGroups: [] }
  await load()
}

async function revealSk(t) {
  try {
    const data = await unwrap(api.get(`/admin/tokens/${t.id}/sk`))
    newSk.value = data.sk
    skTitle.value = `查看 SK - ${t.name}`
    skVisible.value = true
  } catch (e) {
    // 拦截器已提示（旧版本令牌无法找回）
  }
}

async function copySk() {
  await navigator.clipboard.writeText(newSk.value)
  toast('已复制')
}

async function toggleToken(t) {
  await unwrap(api.put(`/admin/tokens/${t.id}`, { status: t.status === 1 ? 0 : 1 }))
  await load()
}

/** 编辑授权分组：allowedGroups 为 null（全部）时预填空选；保存永远回传数组（空数组=恢复「全部」） */
function openEditGroups(t) {
  editTarget.value = t
  editGroups.value = [...(t.allowedGroups || [])]
  editVisible.value = true
}

async function saveGroups() {
  await unwrap(api.put(`/admin/tokens/${editTarget.value.id}`, { allowedGroups: editGroups.value }))
  editVisible.value = false
  toast(editGroups.value.length ? `已更新为 ${editGroups.value.length} 个授权分组` : '已恢复为「全部分组」')
  await load()
}

async function delToken(t) {
  const ok = await confirm({
    title: '删除令牌',
    message: `删除令牌「${t.name}」后该 SK 立即失效，确认？`,
    danger: true, confirmText: '删除'
  })
  if (!ok) return
  await unwrap(api.delete(`/admin/tokens/${t.id}`))
  await load()
}

onMounted(load)
</script>
