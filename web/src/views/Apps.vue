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
          授权分组 {{ t.allowedGroups.length ? t.allowedGroups.join('、') : '全部' }}
          <span v-if="t.expiresAt"> · 过期于 {{ t.expiresAt }}</span>
        </div>
        <div style="display: flex; gap: 6px; flex-wrap: wrap">
          <button class="btn primary sm" @click="revealSk(t)">查看 SK</button>
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

const groupOptions = computed(() => groups.value.map(g => ({ label: g.groupCode, value: g.groupCode })))

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
