<template>
  <div>
    <el-button type="primary" @click="openCreate">新建渠道</el-button>

    <el-table :data="rows" style="margin-top: 12px" size="small" border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="protocol" label="协议族" width="190" />
      <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
      <el-table-column label="Key" width="70">
        <template #default="{ row }">
          <el-tag :type="row.keyConfigured ? 'success' : 'danger'">{{ row.keyConfigured ? '已配置' : '未配置' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="statusReason" label="原因" show-overflow-tooltip />
      <el-table-column label="操作" width="360">
        <template #default="{ row }">
          <el-button size="small" @click="testRun(row)">试跑</el-button>
          <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="manageModels(row)">模型</el-button>
          <el-button size="small" v-if="row.protocol !== 'CRUN_TASK' && row.protocol !== 'VOLCANO_ARK'"
                     @click="syncModels(row)">同步模型</el-button>
          <el-button size="small" type="success" v-if="row.enabled !== 1 || row.status !== 1"
                     @click="enable(row)">启用</el-button>
          <el-button size="small" type="danger" v-else @click="disable(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-drawer v-model="editorVisible" :title="form.id ? '编辑渠道' : '新建渠道'" size="520px">
      <el-form label-width="110px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="协议族">
          <el-select v-model="form.protocol" style="width: 100%">
            <el-option label="OpenAI 同步生图（apiyi/laozhang/uuapi）" value="OPENAI_IMAGES_SYNC" />
            <el-option label="OpenAI 异步任务（nezhagate/uuapi async）" value="OPENAI_ASYNC_TASK" />
            <el-option label="Crun 私有任务协议" value="CRUN_TASK" />
            <el-option label="火山方舟 Seedance（视频阶段）" value="VOLCANO_ARK" disabled />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="如 https://api.apiyi.com/v1（Crun 填 https://api.crunai.com/api/v1）" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.id ? '留空表示不修改' : '上游密钥，AES 加密存储'" />
        </el-form-item>
        <el-form-item label="并发上限"><el-input-number v-model="form.maxConcurrency" :min="1" :max="100" /></el-form-item>
        <el-form-item label="成本单价/张"><el-input-number v-model="form.costPrice" :precision="4" :step="0.01" :min="0" /></el-form-item>
        <el-form-item label="路径覆盖">
          <el-input v-model="form.pathOverridesText" type="textarea" :rows="2"
                    placeholder='可选 JSON，如 {"submitPath":"/images/generations"}' />
        </el-form-item>
        <el-form-item label="适配参数">
          <el-input v-model="form.extraConfigText" type="textarea" :rows="3"
                    placeholder='可选 JSON，异步协议如 {"flavor":"uu","queryPath":"/images/tasks/{id}"}' />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
        <el-button type="primary" style="width: 100%" :loading="saving" @click="save">保存</el-button>
      </el-form>
    </el-drawer>

    <!-- 模型管理 -->
    <el-dialog v-model="modelsVisible" :title="`模型清单 - ${current?.name}`" width="640px">
      <div style="display: flex; gap: 8px; margin-bottom: 10px">
        <el-input v-model="manualModel" placeholder="人工录入上游模型名（Crun 用），如 openai/gpt-image-2-5" />
        <el-button type="primary" @click="addModel">录入</el-button>
      </div>
      <el-table :data="models" size="small" border max-height="380">
        <el-table-column prop="modelCode" label="模型名" />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ row.source === 1 ? '同步' : '人工' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" link @click="delModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { unwrap } from '../api'

const rows = ref([])
const editorVisible = ref(false)
const modelsVisible = ref(false)
const models = ref([])
const manualModel = ref('')
const current = ref(null)
const saving = ref(false)
const emptyForm = () => ({
  id: null, name: '', protocol: 'OPENAI_IMAGES_SYNC', baseUrl: '', apiKey: '',
  maxConcurrency: 5, costPrice: 0, pathOverridesText: '', extraConfigText: '', remark: ''
})
const form = ref(emptyForm())

function statusTag(s) {
  return { 1: { type: 'success', text: '正常' }, 2: { type: 'danger', text: '熔断' },
    3: { type: 'warning', text: '半开' }, 4: { type: 'danger', text: '余额停用' },
    0: { type: 'info', text: '手动停用' } }[s] || { type: 'info', text: s }
}

async function load() {
  rows.value = await unwrap(api.get('/admin/channels'))
}

function parseOrThrow(text, label) {
  if (!text || !text.trim()) return null
  try { return JSON.parse(text) } catch (e) { throw new Error(label + ' 不是合法 JSON') }
}

function openCreate() {
  form.value = emptyForm()
  editorVisible.value = true
}

function openEdit(row) {
  form.value = {
    id: row.id, name: row.name, protocol: row.protocol, baseUrl: row.baseUrl, apiKey: '',
    maxConcurrency: row.maxConcurrency ?? 5, costPrice: row.costPrice ?? 0,
    pathOverridesText: row.pathOverrides ? JSON.stringify(JSON.parse(row.pathOverrides)) : '',
    extraConfigText: row.extraConfig ? JSON.stringify(JSON.parse(row.extraConfig)) : '',
    remark: row.remark || ''
  }
  editorVisible.value = true
}

async function save() {
  saving.value = true
  try {
    const body = {
      name: form.value.name,
      protocol: form.value.protocol,
      baseUrl: form.value.baseUrl,
      apiKey: form.value.apiKey || null,
      pathOverrides: parseOrThrow(form.value.pathOverridesText, '路径覆盖'),
      extraConfig: parseOrThrow(form.value.extraConfigText, '适配参数'),
      maxConcurrency: form.value.maxConcurrency,
      costPrice: form.value.costPrice,
      remark: form.value.remark
    }
    if (form.value.id) await unwrap(api.put(`/admin/channels/${form.value.id}`, body))
    else await unwrap(api.post('/admin/channels', body))
    ElMessage.success('已保存')
    editorVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function testRun(row) {
  const data = await unwrap(api.post(`/admin/channels/${row.id}/test-run`))
  data.result.includes('连通正常')
    ? ElMessage.success(data.result)
    : ElMessage.warning(data.result)
}

async function syncModels(row) {
  const data = await unwrap(api.post(`/admin/channels/${row.id}/sync-models`))
  ElMessage.success(`同步完成，共 ${data.count} 个模型`)
}

async function enable(row) {
  await unwrap(api.post(`/admin/channels/${row.id}/enable`))
  ElMessage.success('已启用')
  await load()
}

async function disable(row) {
  await ElMessageBox.confirm(`确认停用渠道「${row.name}」？`, '提示', { type: 'warning' })
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

async function delModel(row) {
  await unwrap(api.delete(`/admin/channels/${current.value.id}/models/${row.id}`))
  models.value = await unwrap(api.get(`/admin/channels/${current.value.id}/models`))
}

onMounted(load)
</script>
