<template>
  <div>
    <div class="stat-strip">
      <div class="stat-cell"><div><div class="num">{{ tokens.length }}</div><div class="lbl">令牌总数</div></div></div>
      <div class="stat-cell"><div><div class="num">{{ tokens.filter(t => t.status === 1).length }}</div><div class="lbl">启用中</div></div></div>
      <div style="margin-left: auto; align-self: center">
        <el-button type="primary" round @click="createVisible = true">＋ 发令牌</el-button>
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
        <div style="display: flex; gap: 6px">
          <el-button size="small" round type="primary" plain @click="revealSk(t)">查看 SK</el-button>
          <el-button size="small" round @click="toggleToken(t)">{{ t.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button size="small" round type="danger" plain @click="delToken(t)">删除</el-button>
        </div>
      </div>
      <div v-if="!tokens.length" class="db-card" style="text-align: center; color: #9a9a90; padding: 40px">
        还没有令牌，点右上角「发令牌」
      </div>
    </div>

    <!-- 发令牌 -->
    <el-dialog v-model="createVisible" title="发令牌" width="440px">
      <el-form label-width="90px">
        <el-form-item label="令牌名"><el-input v-model="form.name" placeholder="给谁用的，如 banana-mall" @keyup.enter="save" /></el-form-item>
        <el-form-item label="授权分组">
          <el-select v-model="form.allowedGroups" multiple style="width: 100%" placeholder="不选 = 可调用全部分组">
            <el-option v-for="g in groups" :key="g.id" :value="g.groupCode" :label="g.groupCode" />
          </el-select>
        </el-form-item>
        <el-button type="primary" round style="width: 100%" @click="save">生成 SK</el-button>
      </el-form>
    </el-dialog>

    <!-- SK 查看（创建后 / 随时查看共用） -->
    <el-dialog v-model="skVisible" :title="skTitle" width="520px">
      <el-input :model-value="newSk" readonly type="textarea" :rows="2" />
      <div style="margin-top: 10px">
        <el-button type="primary" round @click="copySk">复制</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { unwrap } from '../api'

const tokens = ref([])
const groups = ref([])
const createVisible = ref(false)
const skVisible = ref(false)
const skTitle = ref('SK')
const newSk = ref('')
const form = ref({ name: '', allowedGroups: [] })

async function load() {
  tokens.value = await unwrap(api.get('/admin/tokens'))
  groups.value = await unwrap(api.get('/admin/groups'))
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('令牌名不能为空')
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
  ElMessage.success('已复制')
}

async function toggleToken(t) {
  await unwrap(api.put(`/admin/tokens/${t.id}`, { status: t.status === 1 ? 0 : 1 }))
  await load()
}

async function delToken(t) {
  await ElMessageBox.confirm(`删除令牌「${t.name}」后该 SK 立即失效，确认？`, '提示', { type: 'warning' })
  await unwrap(api.delete(`/admin/tokens/${t.id}`))
  await load()
}

onMounted(load)
</script>
