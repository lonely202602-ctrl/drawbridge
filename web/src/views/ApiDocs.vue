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
    <CodeBlock title="① 提交任务（model 填分组名；文生图可删掉 images；图生图/参考图必填，须公网可访问；数量上限由模型决定）" :code="submitCurl(primaryGroup)" />
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
      <table class="xtable">
        <tbody>
          <tr v-for="row in endpoints" :key="row.path">
            <td style="width: 84px"><span class="badge green">{{ row.m }}</span></td>
            <td style="width: 290px"><code>{{ row.path }}</code></td>
            <td class="hint">{{ row.desc }}</td>
          </tr>
        </tbody>
      </table>
      <div class="hint" style="margin-top: 10px">鉴权：所有接口带 <code>Authorization: Bearer sk-xxx</code></div>
    </div>

    <h3 style="margin: 26px 4px 10px">提交参数（POST /v1/images/tasks）</h3>
    <div class="db-card" style="padding: 14px 18px">
      <table class="xtable">
        <thead><tr><th style="width: 110px">字段</th><th style="width: 80px">必填</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="p in params" :key="p.field">
            <td class="mono">{{ p.field }}</td>
            <td class="hint">{{ p.req }}</td>
            <td>{{ p.desc }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <h3 style="margin: 26px 4px 10px">分辨率参数：aspectRatio（方向）× resolution（档位）</h3>
    <div class="db-card" style="padding: 14px 18px">
      <div class="hint" style="line-height: 2">
        这是<b>网关的统一参数</b>——背后接的是哪家上游你不用管，网关负责翻译成各家听得懂的说法：<br/>
        · <code>aspectRatio</code>：方向意图，<code>W:H</code> 格式。常见支持 <code>1:1</code>、<code>2:3</code>、<code>3:2</code>、<code>3:4</code>、<code>4:3</code>、<code>4:5</code>、<code>5:4</code>、<code>9:16</code>、<code>16:9</code>、<code>21:9</code>；<b>具体支持集合由模型决定</b>（各模型略有差异），超出的比例会返回上游的明确错误，网关不代为限制；<br/>
        · <code>resolution</code>：清晰度档位 <code>1k</code> / <code>2k</code> / <code>4k</code>（各模型对大小写要求不同，网关自动纠正，业务方一律小写即可）；<br/>
        · 两个都<b>可以不传</b>：不传就不发尺寸参数，模型用自己的默认（带参考图修图时保持参考图方向，不会被裁成方图）。
      </div>
      <table class="xtable" style="margin-top: 10px">
        <thead>
          <tr>
            <th style="width: 150px">你传</th>
            <th>背后是 OpenAI 系上游（按官方 30 档对照表精确定档宽×高）</th>
            <th>背后是 Crun 系上游（网关原样直译，模型校验）</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td class="mono">16:9 + 2k</td>
            <td>size = <b>2048×1152</b></td>
            <td>aspect_ratio=<b>16:9</b>、resolution=<b>2k</b></td>
          </tr>
          <tr>
            <td class="mono">21:9 + 2k</td>
            <td>size = <b>2048×864</b>（与 16:9 严格不同档）</td>
            <td>aspect_ratio=<b>21:9</b>、resolution=<b>2k</b></td>
          </tr>
          <tr>
            <td class="mono">9:16 + 4k</td>
            <td>size = <b>2160×3840</b></td>
            <td>aspect_ratio=<b>9:16</b>、resolution=<b>4k</b></td>
          </tr>
          <tr>
            <td class="mono">3:4 + 2k</td>
            <td>size = <b>1536×2048</b></td>
            <td>aspect_ratio=<b>3:4</b>、resolution=<b>2k</b></td>
          </tr>
          <tr>
            <td class="mono">1:1 + 1k</td>
            <td>size = <b>1280×1280</b>（只传比例不传档位按 1k 计）</td>
            <td>aspect_ratio=<b>1:1</b>、resolution=<b>1k</b></td>
          </tr>
          <tr>
            <td class="mono">都不传</td>
            <td>不带 size（模型默认）</td>
            <td>不带参数（模型默认）</td>
          </tr>
        </tbody>
      </table>
      <div class="hint" style="margin-top: 10px; line-height: 1.9">
        特殊模型的精确尺寸（如某些模型只认 1792×1024）由管理员按渠道配置映射，业务方无感；旧版 <code>size</code>
        宽×高写法仍兼容（原样透传），<b>新接入请统一用上面两个参数</b>。
      </div>

      <h4 style="margin: 16px 0 6px">附：OpenAI 系上游「比例 × 档位」完整 30 档对照表（网关自动查表）</h4>
      <table class="xtable">
        <thead>
          <tr>
            <th style="width: 90px">比例</th>
            <th>1k</th>
            <th>2k</th>
            <th>4k</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in size30" :key="r.ratio">
            <td class="mono">{{ r.ratio }}</td>
            <td class="mono">{{ r.k1 }}</td>
            <td class="mono">{{ r.k2 }}</td>
            <td class="mono">{{ r.k4 }}</td>
          </tr>
        </tbody>
      </table>
      <div class="hint" style="margin-top: 8px; line-height: 1.9">
        · 上表是行业通用 10 比例全集（枚举外的比例按对数距离就近吸附，如 7:4 → 16:9 档）；<br/>
        · <b>nano 系（Gemini 原生）不走此表</b>：比例原样直传，另有 NB2 独有的 1:4、4:1、1:8、8:1 超宽超窄比例；<br/>
        · Crun 系同样原样直译、由模型校验；各模型实际支持的子集不同，超出时上游明确报错。
      </div>
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
import { toast } from '../ui'
import api, { unwrap } from '../api'

const groups = ref([])

// OpenAI 系上游「比例×档位」官方 30 档对照表（与后端 Resolutions.SIZE_30 同源，apiyi gpt-image-2-vip 概览页）
const size30 = [
  { ratio: '1:1', k1: '1280x1280', k2: '2048x2048', k4: '2880x2880' },
  { ratio: '2:3', k1: '848x1280', k2: '1360x2048', k4: '2336x3520' },
  { ratio: '3:2', k1: '1280x848', k2: '2048x1360', k4: '3520x2336' },
  { ratio: '3:4', k1: '960x1280', k2: '1536x2048', k4: '2480x3312' },
  { ratio: '4:3', k1: '1280x960', k2: '2048x1536', k4: '3312x2480' },
  { ratio: '4:5', k1: '1024x1280', k2: '1632x2048', k4: '2560x3216' },
  { ratio: '5:4', k1: '1280x1024', k2: '2048x1632', k4: '3216x2560' },
  { ratio: '9:16', k1: '720x1280', k2: '1152x2048', k4: '2160x3840' },
  { ratio: '16:9', k1: '1280x720', k2: '2048x1152', k4: '3840x2160' },
  { ratio: '21:9', k1: '1280x544', k2: '2048x864', k4: '3840x1632' }
]

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
  { field: 'n', req: '不要传', desc: '一个任务固定出一张图；传 n>1 会被拒绝' },
  { field: 'resolution', req: '否', desc: '清晰度档位 1k/2k/4k；不传就不发该参数，由模型用自己的默认' },
  { field: 'aspectRatio', req: '否', desc: '方向意图 W:H；常见全集 1:1、2:3、3:2、3:4、4:3、4:5、5:4、9:16、16:9、21:9（由模型决定，超出上游明确报错）；不传就不发尺寸参数' },
  { field: 'image / images', req: '否', desc: '参考图 URL（必须公网可访问）；数量上限由模型决定（gpt-image 系 14 张、nano 系更少），超出时上游返回明确错误' },
  { field: 'options', req: '否', desc: '透传参数（quality、output_format 等，模型支持才生效）' }
]

function submitCurl(g) {
  return `curl -X POST http://localhost:8180/v1/images/tasks \
  -H "Authorization: Bearer <你的SK>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "${g}",
    "prompt": "a cute cat",
    "resolution": "1k",
    "aspectRatio": "16:9",
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
    toast('已复制')
  }
  return h('div', { class: 'db-card', style: 'margin-bottom: 12px; padding: 12px 16px' }, [
    h('div', { style: 'display:flex; justify-content: space-between; align-items: center; margin-bottom: 8px' }, [
      h('b', { style: 'font-size: 13px' }, props.title),
      h('button', { class: 'btn ghost sm', onClick: copy }, () => '复制')
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
