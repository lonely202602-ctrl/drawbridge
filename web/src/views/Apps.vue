<template>
  <div>
    <el-button type="primary" @click="createAppVisible = true">新建应用</el-button>
    <el-alert type="info" :closable="false" style="margin: 10px 0"
              title="应用 = 一个业务接入方；令牌(SK)挂在应用下，创建时只显示一次。" />

    <el-table :data="apps" size="small" border style="margin-top: 8px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="应用" />
      <el-table-column prop="contact" label="联系人" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openTokens(row)">令牌管理</el-button>
          <el-button size="small" type="danger" v-if="row.enabled === 1" @click="toggleApp(row, 0)">停用</el-button>
          <el-button size="small" type="success" v-else @click="toggleApp(row, 1)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 令牌管理 -->
    <el-dialog v-model="tokensVisible" :title="`令牌管理 - ${current?.name}`" width="820px">
      <el-button type="primary" size="small" @click="createTokenVisible = true" style="margin-bottom: 10px">发新令牌</el-button>
      <el-table :data="tokens" size="small" border>
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="prefix" label="SK 前缀" width="160" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="额度" width="160">
          <template #default="{ row }">{{ row.quotaUsed }} / {{ row.quotaTotal }}</template>
        </el-table-column>
        <el-table-column label="授权分组">
          <template #default="{ row }">{{ row.allowedGroups.length ? row.allowedGroups.join('、') : '全部' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button size="small" link @click="toggleToken(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-button size="small" type="danger" link @click="delToken(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 新建应用 -->
    <el-dialog v-model="createAppVisible" title="新建应用" width="440px">
      <el-form label-width="90px">
        <el-form-item label="应用名"><el-input v-model="appForm.name" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="appForm.contact" /></el-form-item>
        <el-button type="primary" style="width: 100%" @click="saveApp">保存</el-button>
      </el-form>
    </el-dialog>

    <!-- 发令牌 -->
    <el-dialog v-model="createTokenVisible" title="发新令牌" width="460px">
      <el-form label-width="100px">
        <el-form-item label="令牌名"><el-input v-model="tokenForm.name" /></el-form-item>
        <el-form-item label="总额度(元)"><el-input-number v-model="tokenForm.quotaTotal" :min="0" :precision="2" />
          <span style="color:#999;font-size:12px">0 = 不限额</span></el-form-item>
        <el-form-item label="授权分组">
          <el-select v-model="tokenForm.allowedGroups" multiple style="width: 100%" placeholder="不选 = 全部分组">
            <el-option v-for="g in groups" :key="g.id" :value="g.groupCode" :label="g.groupCode" />
          </el-select>
        </el-form-item>
        <el-button type="primary" style="width: 100%" @click="saveToken">生成 SK</el-button>
      </el-form>
    </el-dialog>

    <!-- SK 只显一次 -->
    <el-dialog v-model="skVisible" title="⚠️ SK 只显示这一次" width="520px">
      <el-input :model-value="newSk" readonly type="textarea" :rows="2" />
      <div style="margin-top: 10px; display: flex; gap: 10px">
        <el-button type="primary" @click="copySk">复制</el-button>
        <span style="color: #e6a23c; align-self: center">关闭后无法再次查看，请立即保存</span>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { unwrap } from '../api'

const apps = ref([])
const groups = ref([])
const tokens = ref([])
const current = ref(null)
const createAppVisible = ref(false)
const createTokenVisible = ref(false)
const tokensVisible = ref(false)
const skVisible = ref(false)
const newSk = ref('')
const appForm = ref({ name: '', contact: '' })
const tokenForm = ref({ name: '', quotaTotal: 0, allowedGroups: [] })

async function load() {
  apps.value = await unwrap(api.get('/admin/apps'))
  groups.value = await unwrap(api.get('/admin/groups'))
}

async function saveApp() {
  await unwrap(api.post('/admin/apps', appForm.value))
  ElMessage.success('已创建')
  createAppVisible.value = false
  appForm.value = { name: '', contact: '' }
  await load()
}

async function toggleApp(row, enabled) {
  await unwrap(api.post(`/admin/apps/${row.id}/${enabled === 1 ? 'enable' : 'disable'}`))
  await load()
}

async function openTokens(row) {
  current.value = row
  tokens.value = await unwrap(api.get(`/admin/apps/${row.id}/tokens`))
  tokensVisible.value = true
}

async function saveToken() {
  const body = {
    name: tokenForm.value.name,
    quotaTotal: tokenForm.value.quotaTotal > 0 ? tokenForm.value.quotaTotal : null,
    allowedGroups: tokenForm.value.allowedGroups
  }
  const data = await unwrap(api.post(`/admin/apps/${current.value.id}/tokens`, body))
  newSk.value = data.sk
  createTokenVisible.value = false
  skVisible.value = true
  tokens.value = await unwrap(api.get(`/admin/apps/${current.value.id}/tokens`))
}

async function copySk() {
  await navigator.clipboard.writeText(newSk.value)
  ElMessage.success('已复制')
}

async function toggleToken(row) {
  await unwrap(api.put(`/admin/tokens/${row.id}`, { status: row.status === 1 ? 0 : 1 }))
  tokens.value = await unwrap(api.get(`/admin/apps/${current.value.id}/tokens`))
}

async function delToken(row) {
  await ElMessageBox.confirm('删除后该 SK 立即失效，确认？', '提示', { type: 'warning' })
  await unwrap(api.delete(`/admin/tokens/${row.id}`))
  tokens.value = await unwrap(api.get(`/admin/apps/${current.value.id}/tokens`))
}

onMounted(load)
</script>
