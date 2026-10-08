<template>
  <div>
    <el-button type="primary" @click="openCreate">新建分组</el-button>
    <el-alert type="info" :closable="false" style="margin: 10px 0"
              title="分组 = 业务方调用的 model 名。一个分组挂多个上游成员即互为备份，策略决定请求怎么分配。" />

    <el-table :data="rows" size="small" border style="margin-top: 8px">
      <el-table-column prop="groupCode" label="分组编码（model 名）" />
      <el-table-column prop="displayName" label="显示名" />
      <el-table-column label="策略" width="110">
        <template #default="{ row }">{{ strategyText(row.strategy) }}</template>
      </el-table-column>
      <el-table-column prop="bizPrice" label="售价/张(元)" width="110" />
      <el-table-column prop="memberCount" label="成员数" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="manageMembers(row)">成员管理</el-button>
          <el-button size="small" type="danger" v-if="row.enabled === 1" @click="del(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分组编辑 -->
    <el-dialog v-model="editorVisible" :title="form.id ? '编辑分组' : '新建分组'" width="480px">
      <el-form label-width="120px">
        <el-form-item label="分组编码"><el-input v-model="form.groupCode" placeholder="业务方填的 model，如 image2.5" :disabled="!!form.id" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="路由策略">
          <el-select v-model="form.strategy" style="width: 100%">
            <el-option label="故障转移（按顺序，稳）" value="FAILOVER" />
            <el-option label="轮询（均匀轮流）" value="POLLING" />
            <el-option label="随机（等概率）" value="RANDOM" />
            <el-option label="加权分配（按权重）" value="WEIGHTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="售价/张(元)"><el-input-number v-model="form.bizPrice" :precision="4" :step="0.01" :min="0" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
        <el-button type="primary" style="width: 100%" @click="save">保存</el-button>
      </el-form>
    </el-dialog>

    <!-- 成员管理 -->
    <el-dialog v-model="membersVisible" :title="`成员管理 - ${current?.groupCode}`" width="760px">
      <el-table :data="members" size="small" border style="margin-bottom: 10px">
        <el-table-column prop="channelName" label="渠道" />
        <el-table-column prop="upstreamModel" label="上游模型名" />
        <el-table-column prop="sortOrder" label="顺序" width="70" />
        <el-table-column prop="weight" label="权重" width="70" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" link @click="toggleMember(row)">{{ row.enabled === 1 ? '停用' : '启用' }}</el-button>
            <el-button size="small" type="danger" link @click="delMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider>添加成员</el-divider>
      <el-form inline>
        <el-form-item label="渠道">
          <el-select v-model="newMember.channelId" style="width: 180px" @change="loadChannelModels">
            <el-option v-for="c in channels" :key="c.id" :value="c.id"
                       :label="`${c.name}（${c.protocol === 'OPENAI_IMAGES_SYNC' ? '同步' : c.protocol === 'OPENAI_ASYNC_TASK' ? '异步' : 'Crun'}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="上游模型">
          <el-select v-model="newMember.upstreamModel" filterable allow-create style="width: 240px">
            <el-option v-for="m in channelModels" :key="m.id" :value="m.modelCode" :label="m.modelCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="顺序"><el-input-number v-model="newMember.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="权重"><el-input-number v-model="newMember.weight" :min="1" /></el-form-item>
        <el-button type="primary" @click="addMember">添加</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { unwrap } from '../api'

const rows = ref([])
const channels = ref([])
const channelModels = ref([])
const editorVisible = ref(false)
const membersVisible = ref(false)
const members = ref([])
const current = ref(null)
const form = ref({ id: null, groupCode: '', displayName: '', strategy: 'FAILOVER', bizPrice: 0.02, remark: '' })
const newMember = ref({ channelId: null, upstreamModel: '', sortOrder: 0, weight: 1 })

function strategyText(s) {
  return { FAILOVER: '故障转移', POLLING: '轮询', RANDOM: '随机', WEIGHTED: '加权' }[s] || s
}

async function load() {
  rows.value = await unwrap(api.get('/admin/groups'))
  channels.value = await unwrap(api.get('/admin/channels'))
}

function openCreate() {
  form.value = { id: null, groupCode: '', displayName: '', strategy: 'FAILOVER', bizPrice: 0.02, remark: '' }
  editorVisible.value = true
}

function openEdit(row) {
  form.value = { id: row.id, groupCode: row.groupCode, displayName: row.displayName,
    strategy: row.strategy, bizPrice: Number(row.bizPrice), remark: row.remark }
  editorVisible.value = true
}

async function save() {
  if (form.value.id) await unwrap(api.put(`/admin/groups/${form.value.id}`, form.value))
  else await unwrap(api.post('/admin/groups', form.value))
  ElMessage.success('已保存')
  editorVisible.value = false
  await load()
}

async function del(row) {
  await ElMessageBox.confirm(`停用分组「${row.groupCode}」？业务方将无法再调用该模型。`, '提示', { type: 'warning' })
  await unwrap(api.delete(`/admin/groups/${row.id}`))
  await load()
}

async function manageMembers(row) {
  current.value = row
  const detail = await unwrap(api.get(`/admin/groups/${row.id}`))
  members.value = detail.members
  membersVisible.value = true
}

async function loadChannelModels(channelId) {
  newMember.value.upstreamModel = ''
  channelModels.value = await unwrap(api.get(`/admin/channels/${channelId}/models`))
}

async function addMember() {
  if (!newMember.value.channelId || !newMember.value.upstreamModel) {
    ElMessage.warning('请选择渠道并填写上游模型名')
    return
  }
  await unwrap(api.post(`/admin/groups/${current.value.id}/members`, newMember.value))
  await manageMembers(current.value)
  await load()
}

async function toggleMember(row) {
  await unwrap(api.put(`/admin/groups/${current.value.id}/members/${row.id}`, {
    upstreamModel: row.upstreamModel, weight: row.weight, sortOrder: row.sortOrder,
    enabled: row.enabled === 1 ? 0 : 1
  }))
  await manageMembers(current.value)
}

async function delMember(row) {
  await unwrap(api.delete(`/admin/groups/${current.value.id}/members/${row.id}`))
  await manageMembers(current.value)
  await load()
}

onMounted(load)
</script>
