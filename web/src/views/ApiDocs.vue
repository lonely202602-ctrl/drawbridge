<template>
  <div style="max-width: 920px">
    <div class="db-card" style="margin-bottom: 14px; padding: 16px 18px">
      <b>三步接入</b>
      <div class="hint" style="margin-top: 6px">
        ① 到「令牌」页发一个 SK（复制保存） →
        ② 用下面的 curl 提交任务（model 填分组名，已自动填入你配置的分组） →
        ③ 拿 taskId 轮询查询接口，SUCCEEDED 后取 result.urls（结果 URL 有有效期，请及时转存）
      </div>
    </div>

    <h3 style="margin: 22px 4px 10px">快速测试</h3>
    <CodeBlock title="① 提交任务（model 填分组名；文生图可删掉 images；图生图/参考图必填，须公网可访问，最多 4 张）" :code="submitCurl(primaryGroup)" />
    <div class="hint" style="margin: -6px 4px 12px">
      可用分组：<template v-for="(g, i) in groupCodes" :key="g"><code class="pill">{{ g }}</code>{{ i < groupCodes.length - 1 ? ' / ' : '' }}</template>
      ——换分组只需替换 model 值。可选请求头 <code>X-Idempotency-Key: 业务唯一键</code>（防重复提交）
    </div>
    <CodeBlock title="② 轮询任务（把 taskId 换成上一步返回的，每 2~5 秒查一次）" :code="queryCurl" />
    <div v-if="!groupCodes.length" class="db-card hint" style="padding: 14px 18px; margin-bottom: 10px">
      还没有模型分组——先到「模型分组」页建一个，这里会自动生成对应 curl。
    </div>

    <h3 style="margin: 26px 4px 10px">接口一览</h3>
    <div class="db-card" style="padding: 4px 18px 14px">
      <el-table :data="endpoints" size="small" :show-header="false">
        <el-table-column prop="m" width="90">
          <template #default="{ row }"><span class="badge green">{{ row.m }}</span></template>
        </el-table-column>
        <el-table-column prop="path" width="300">
          <template #default="{ row }"><code>{{ row.path }}</code></template>
        </el-table-column>
        <el-table-column prop="desc" />
      </el-table>
      <div class="hint" style="margin-top: 10px">鉴权：所有接口带 <code>Authorization: Bearer sk-xxx</code></div>
    </div>

    <h3 style="margin: 26px 4px 10px">提交参数（POST /v1/images/tasks）</h3>
    <div class="db-card" style="padding: 14px 18px">
      <el-table :data="params" size="small">
        <el-table-column prop="field" label="字段" width="120" />
        <el-table-column prop="req" label="必填" width="70" />
        <el-table-column prop="desc" label="说明" />
      </el-table>
    </div>

    <h3 style="margin: 26px 4px 10px">任务状态与失败码</h3>
    <div class="db-card" style="padding: 14px 18px">
      <b style="font-size: 13px">status</b>
      <div class="hint" style="margin: 6px 0 12px">
        SUBMITTED 已受理 → RUNNING 生成中 → <b>SUCCEEDED</b> 成功（取 result.urls）/ <b>FAILED</b> 失败（看 error）
      </div>
      <b style="font-size: 13px">error.code（失败一律不计费）</b>
      <div class="hint" style="margin-top: 6px; line-height: 1.9">
        CONTENT_REJECTED 内容审核未通过 · PARAM_INVALID 上游不支持的参数 · UPSTREAM_TASK_FAILED 上游生成失败<br/>
        TIMEOUT 超时（默认 15 分钟）· UPSTREAM_ALL_FAILED 多渠道重试后仍失败 · NO_AVAILABLE_CHANNEL 暂无可用渠道
      </div>
    </div>

    <h3 style="margin: 26px 4px 10px">限流（按令牌计数，超限返回 429/42901）</h3>
    <div class="db-card hint" style="padding: 14px 18px; line-height: 2">
      提交：<b>120 次/分钟/令牌</b> —— 查询：<b>600 次/分钟/令牌</b>（两者分开计数，互不挤占）。<br/>
      轮询建议每 2~5 秒一次，正常业务不会触顶；收到 429 请退避后重试。阈值可在「系统设置」热调（openapi.rate_per_min / openapi.query_rate_per_min）。
    </div>

    <h3 style="margin: 26px 4px 10px">注意事项</h3>
    <div class="db-card hint" style="padding: 14px 18px; line-height: 2">
      1. 结果 URL 为上游原样返回，<b>有有效期</b>，成功后请立即下载转存。<br/>
      2. 部分模型（gpt-image 系）上游回 base64：result.urls 为 <code>data:image/...</code> 形式，提交后通过查询接口同样能拿到（任务存档期内可查），请及时下载转存。<br/>
      3. 幂等：提交时带 <code>X-Idempotency-Key: 业务唯一键</code>，重复提交返回同一任务不重复执行。<br/>
      4. 完整字段说明见仓库 <code>docs/API.md</code>。
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, h } from 'vue'
import { ElMessage } from 'element-plus'
import api, { unwrap } from '../api'

const groups = ref([])

const groupCodes = computed(() => groups.value.filter(g => g.enabled === 1).map(g => g.groupCode))
const primaryGroup = computed(() => groupCodes.value[0] || '你的分组名')

const endpoints = [
  { m: 'POST', path: '/v1/images/tasks', desc: '提交生图任务，立即返回 taskId（不等上游，结果轮询获取）' },
  { m: 'GET', path: '/v1/images/tasks/{taskId}', desc: '查询任务状态与结果' },
  { m: 'GET', path: '/v1/images/tasks?page=1&size=20', desc: '本令牌的任务列表（可按 status 过滤）' },
  { m: 'GET', path: '/v1/images/models', desc: '可用模型分组列表' }
]

const params = [
  { field: 'model', req: '是', desc: '模型分组名（本文档页自动带出你配置的分组）' },
  { field: 'prompt', req: '与 image 二选一', desc: '提示词，≤4000 字符' },
  { field: 'n', req: '否', desc: '张数 1-6，默认 1' },
  { field: 'size', req: '否', desc: '如 1024x1024，是否支持取决于模型' },
  { field: 'aspectRatio', req: '否', desc: '如 3:2（部分模型支持）' },
  { field: 'image / images', req: '否', desc: '参考图 URL（必须公网可访问），最多 4 张' },
  { field: 'options', req: '否', desc: '透传参数（quality、output_format 等，模型支持才生效）' }
]

function submitCurl(g) {
  return `curl -X POST http://localhost:8180/v1/images/tasks \
  -H "Authorization: Bearer <你的SK>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "${g}",
    "prompt": "a cute cat",
    "n": 1,
    "size": "1024x1024",
    "aspectRatio": "1:1",
    "images": [
      "https://example.com/base.png",
      "https://example.com/ref1.png"
    ]
  }'`
}

const queryCurl = `curl http://localhost:8180/v1/images/tasks/<taskId> \\
  -H "Authorization: Bearer <你的SK>"`

function CodeBlock(props) {
  const copy = async () => {
    await navigator.clipboard.writeText(props.code)
    ElMessage.success('已复制')
  }
  return h('div', { class: 'db-card', style: 'margin-bottom: 12px; padding: 12px 16px' }, [
    h('div', { style: 'display:flex; justify-content: space-between; align-items: center; margin-bottom: 8px' }, [
      h('b', { style: 'font-size: 13px' }, props.title),
      h('el-button', { size: 'small', round: true, onClick: copy }, () => '复制')
    ]),
    h('pre', {
      style: 'background:#f7f6f2;border:1px solid #eeede6;border-radius:10px;padding:12px 14px;font-size:12px;overflow:auto;white-space:pre-wrap;word-break:break-all;margin:0'
    }, props.code)
  ])
}
CodeBlock.props = ['title', 'code']

onMounted(async () => {
  groups.value = await unwrap(api.get('/admin/groups'))
})
</script>

<style scoped>
.pill { background: #ecf2ee; color: #4a7c59; padding: 2px 10px; border-radius: 999px; font-weight: 600; }
code { background: #f1efe8; padding: 1px 6px; border-radius: 6px; font-size: 12px; }
</style>
