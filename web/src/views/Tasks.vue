<template>
  <div>
    <el-form inline>
      <el-form-item label="任务号"><el-input v-model="query.taskId" placeholder="模糊搜索" clearable style="width: 220px" /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable style="width: 130px">
          <el-option v-for="s in ['SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED']" :key="s" :value="s" :label="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="分组"><el-input v-model="query.groupCode" clearable style="width: 140px" /></el-form-item>
      <el-button type="primary" @click="load(1)">查询</el-button>
    </el-form>

    <el-table :data="rows" size="small" border>
      <el-table-column prop="taskId" label="任务号" width="210" />
      <el-table-column prop="groupCode" label="分组" width="110" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="{ SUCCEEDED: 'success', FAILED: 'danger', RUNNING: 'warning', SUBMITTED: 'info' }[row.status]">
            {{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channelName" label="受理渠道" width="120" />
      <el-table-column prop="attemptCount" label="重试次数" width="90" />
      <el-table-column prop="errorCode" label="错误码" width="160" />
      <el-table-column prop="errorMsg" label="错误信息" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px" background layout="prev, pager, next, total"
                   :total="total" :page-size="query.size" :current-page="query.page"
                   @current-change="(p) => load(p)" />

    <!-- 详情抽屉：重试时间线 + 双向请求体 + 结果 -->
    <el-drawer v-model="detailVisible" title="任务详情" size="640px">
      <template v-if="detail">
        <h4>重试时间线（每次实际调用了哪个上游）</h4>
        <el-table :data="detail.attempts" size="small" border>
          <el-table-column prop="attemptNo" label="#" width="50" />
          <el-table-column prop="phase" label="阶段" width="80" />
          <el-table-column prop="channelName" label="上游" width="110" />
          <el-table-column prop="upstreamModel" label="模型" width="150" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.outcome === 'SUCCESS' ? 'success' : 'danger'">
                {{ row.phase === 'SUBMIT' && row.outcome === 'SUCCESS' ? '受理成功' : row.outcome }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="errorKind" label="错误类别" width="150" />
          <el-table-column prop="costMs" label="耗时ms" width="80" />
          <el-table-column prop="upstreamUrl" label="请求地址" min-width="200" show-overflow-tooltip />
        </el-table>
        <div class="hint" style="margin-top: 6px">
          SUBMIT 阶段的「受理成功」= 上游已接收任务并返回任务号，图片由网关后台轮询推进；任务最终结果以上方任务状态为准。
        </div>

        <h4>① 业务方请求体</h4>
        <pre class="json-block">{{ pretty(detail.task.bizRequest) }}</pre>
        <h4>② 封装后发上游的请求体</h4>
        <div v-if="detail.task.upstreamUrl" class="hint" style="margin-bottom: 4px; word-break: break-all">
          请求地址：{{ detail.task.upstreamUrl }}
        </div>
        <pre class="json-block">{{ pretty(detail.task.upstreamRequest) }}</pre>
        <h4>③ 任务响应结果</h4>
        <pre class="json-block">{{ pretty(detail.task.result) || '（无）' }}</pre>
        <div v-if="detail.task.errorMsg" style="color: #f56c6c">失败原因：{{ detail.task.errorMsg }}</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import api, { unwrap } from '../api'

const rows = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, status: '', groupCode: '', taskId: '' })
const detailVisible = ref(false)
const detail = ref(null)

async function load(page) {
  if (page) query.page = page
  const data = await unwrap(api.get('/admin/tasks', { params: { ...query } }))
  rows.value = data.records
  total.value = data.total
}

async function openDetail(row) {
  detail.value = await unwrap(api.get(`/admin/tasks/detail/${row.id}`))
  detailVisible.value = true
}

function pretty(json) {
  if (!json) return ''
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch (e) { return json }
}

onMounted(() => load())
</script>

<style scoped>
.json-block {
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px;
  font-size: 12px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
