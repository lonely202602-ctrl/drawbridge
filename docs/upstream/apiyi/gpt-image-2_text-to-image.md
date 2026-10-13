> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 文生图 API 参考

> gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2 文生图 API 参考与在线调试 — 任意合法尺寸（含 4K），按 token 计费，三款同价同参数

<Info>
  右侧的交互式 Playground 支持直接在线调试。请在 **Authorization** 中填入你的 API Key（格式：`Bearer sk-xxx`），输入 prompt、选择 size / quality 后一键发送即可。
</Info>

<Tip>
  **场景说明**：本页用于「文本生成图片」。只需输入提示词即可，无需上传任何图片。如需根据现有图片做编辑、多图融合或 mask 局部重绘，请使用 [图片编辑接口](/api-capabilities/gpt-image-2/image-edit)。
</Tip>

<Warning>
  **🖥️ 浏览器 Playground 限制（重要）**

  本接口的响应包含**纯 base64 字符串**（数 MB 量级）。受浏览器渲染限制，右侧 Playground 在收到响应后**可能弹出** `请求时发生错误: unable to complete request` ——**实际请求已经成功**，只是浏览器无法把这么长的 base64 显示出来。

  **推荐做法**（小白零踩坑）：

  * **直接复制下方"代码示例"中的 Python / Node.js / cURL 到本地运行**，代码会自动 `base64.b64decode` 并把图片**保存为本地文件**。
  * 如要在浏览器里试 Playground，把 `size` 设为最小档（如 `1024x1024`）、`quality` 改为 `low`，缩小响应体积。
</Warning>

<Info>
  图片 API 全部为**同步调用**：没有异步任务 ID，客户端断开连接结果即丢失、但请求仍会计费。请为本模型设置足够大的 timeout，详见 [图片 API 调用须知与最佳实践](/api-capabilities/image-api-best-practices)。
</Info>

<Warning>
  **⚠️ 不支持的参数**

  * `input_fidelity` —— 三款模型均强制启用高保真，传了会 400 报错（2.5 实测 2026-09-09：`does not support the 'input_fidelity' parameter`；从 1.5 迁移时直接删掉这一行）

  **超过 `2560×1440` 的输出仍属实验性**，生产环境建议优先用预设尺寸：`2048x1152` / `2048x2048` / `3840x2160`。
</Warning>

## 代码示例

### Python（OpenAI SDK 直连）

```python theme={null}
from openai import OpenAI
import base64

client = OpenAI(
    api_key="sk-your-api-key",
    base_url="https://api.apiyi.com/v1"
)

resp = client.images.generate(
    model="gpt-image-2.5-flare",
    prompt="赛博朋克城市雨夜，霓虹招牌特写，电影画幅",
    size="2048x1152",
    quality="high",
    output_format="jpeg",
    output_compression=85
)

# b64_json 是纯 base64（无前缀），需要自己 decode 写文件
with open("out.jpg", "wb") as f:
    f.write(base64.b64decode(resp.data[0].b64_json))
```

### Python（原生 requests）

```python theme={null}
import requests
import base64

API_KEY = "sk-your-api-key"

response = requests.post(
    "https://api.apiyi.com/v1/images/generations",
    headers={
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    },
    json={
        "model": "gpt-image-2.5-flare",
        "prompt": "横版 2K 海边日落老灯塔，电影画幅",
        "size": "2048x1152",
        "quality": "high"
    },
    timeout=360  # high + 2K/4K 实测可能 3-5 分钟，按 120s 配会大量误超时
).json()

with open("out.png", "wb") as f:
    f.write(base64.b64decode(response["data"][0]["b64_json"]))
```

### cURL

```bash theme={null}
curl -X POST "https://api.apiyi.com/v1/images/generations" \
  -H "Authorization: Bearer sk-your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-image-2.5-flare",
    "prompt": "一只戴墨镜的橘猫坐在海边吧台，电影画幅",
    "size": "2048x1152",
    "quality": "high",
    "output_format": "jpeg",
    "output_compression": 85
  }'
```

### Node.js（原生 fetch）

```javascript theme={null}
import fs from 'node:fs';

const resp = await fetch('https://api.apiyi.com/v1/images/generations', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer sk-your-api-key'
    },
    body: JSON.stringify({
        model: 'gpt-image-2.5-flare',
        prompt: '极简线条风格的猫咪 LOGO',
        size: '1024x1024',
        quality: 'medium'
    })
});

const { data } = await resp.json();
// b64_json 是纯 base64（无前缀），需自己 decode
fs.writeFileSync('logo.png', Buffer.from(data[0].b64_json, 'base64'));
```

### 浏览器 JavaScript（直接渲染）

```javascript theme={null}
const resp = await fetch('https://api.apiyi.com/v1/images/generations', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer sk-your-api-key'
    },
    body: JSON.stringify({
        model: 'gpt-image-2.5-flare',
        prompt: '水彩风的北欧极光',
        size: '1536x1024',
        quality: 'high'
    })
});

const { data } = await resp.json();
// 浏览器渲染需自己拼 data URL 前缀
document.getElementById('img').src = `data:image/png;base64,${data[0].b64_json}`;
```

## 参数说明速查

| 参数 | 类型 | 必填 | 默认 | 说明 |
| - | - | - | - | - |
| `model` | string | 是 | — | `gpt-image-2.5-flare`（速度优先，日常首选）/ `gpt-image-2.5-sunburst`（画质与编辑精度优先）/ `gpt-image-2`（上一代，仍可用）；生产环境可锁日期快照 `gpt-image-2.5-flare-2026-09-08` / `gpt-image-2.5-sunburst-2026-09-08`。三款同价同参数 |
| `prompt` | string | 是 | — | 提示词，支持中英文；最长 32,000 字符（原厂上限，按字符不按 token），长资料先提炼再传，见 [长提示词篇](/api-capabilities/image-long-prompt) |
| `size` | string | 否 | `auto` | 输出尺寸，预设或满足约束的自定义尺寸 |
| `quality` | string | 否 | `auto` | `low` / `medium` / `high` / `xhigh` / `max` / `auto`；`xhigh` / `max` 是 2.5 新增档位，`gpt-image-2` 只到 `high` |
| `output_format` | string | 否 | `png` | `png` / `jpeg` / `webp` |
| `output_compression` | int | 否 | — | 0–100，仅 `jpeg` / `webp` 生效 |
| `background` | string | 否 | `auto` | `transparent` / `opaque` / `auto`；传 `transparent` 时 `output_format` 必须是 `png` 或 `webp`，配 `jpeg` 会 400，见 [透明背景 FAQ](/faq/image-transparent-background) |
| `moderation` | string | 否 | `auto` | `auto` / `low`（低强度审核） |
| `n` | int | 否 | 1 | 仅支持 1 |

<Warning>
  **`quality` 不要传旧版 DALL·E 的 `standard` / `hd`。** 只接受 `low` / `medium` / `high` / `xhigh` / `max` / `auto` 六个官方枚举值（`xhigh` / `max` 仅 2.5 两款接受）。旧值在不同后端渠道下行为不一致：有时直接 400 报错（`invalid_value`），有时被静默忽略、按 `auto` 档跑出结果（费用不可控）。请始终显式传官方枚举值之一。
</Warning>

<Tip>
  详细的参数约束、可选值、示例请查看右侧 Playground 中的字段说明，所有 enum 字段均支持下拉选择。
</Tip>

## 响应格式

```json theme={null}
{
    "created": 1776832476,
    "data": [
        {
            "b64_json": "iVBORw0KGgoAAAANSUhEUgAA..."
        }
    ],
    "usage": {
        "input_tokens": 17,
        "input_tokens_details": {
            "image_tokens": 0,
            "text_tokens": 17
        },
        "output_tokens": 196,
        "output_tokens_details": {
            "image_tokens": 196,
            "text_tokens": 0
        },
        "total_tokens": 213
    }
}
```

<Warning>
  **⚠️ b64\_json 字段是纯 base64**，**不含** `data:image/...;base64,` 前缀。客户端需要：

  * **写文件**：`base64.b64decode(b64_str)` → 写入磁盘
  * **浏览器渲染**：自行拼前缀 `data:image/png;base64,` + b64

  `gpt-image-2-all` / `gpt-image-2-vip` 实测（2026-07）同样返回纯 base64，但其历史版本曾带前缀——跨模型复用代码时建议统一做 `startsWith('data:')` 检测。
</Warning>

<Info>
  `usage` 字段反映本次实际计费的 token 数，`input_tokens_details` / `output_tokens_details` 把文本、图片两段 token 拆开列出（纯文生图时 `image_tokens` 恒为 0）。详细的字段说明和自行核算公式见 [概览页「如何查看每次调用的真实 token 数」](/api-capabilities/gpt-image-2/overview#如何查看每次调用的真实-token-数)。
</Info>


## OpenAPI

````yaml api-reference/gpt-image-2-generate-openapi.yaml POST /v1/images/generations
openapi: 3.1.0
info:
  title: gpt-image-2.5 / 2 文生图 API
  description: >
    OpenAI GPT-Image 2.5 / 2 系列（`gpt-image-2.5-flare` / `gpt-image-2.5-sunburst`
    / `gpt-image-2`）— 文生图接口。三款同价同参数。


    - 任意合法尺寸（1K / 2K / 4K，最大 3840×2160）

    - 画质档位：low / medium / high / xhigh / max / auto（xhigh / max 仅 2.5 两款接受）

    - 输出格式：png（默认）/ jpeg / webp

    - 中文提示词原生支持

    - 单次出图 1 张（n=1）

    - 速度：flare 最快，sunburst 与 gpt-image-2 更慢（高画质 4K 可达数分钟）

    - 支持透明背景：`background: transparent` 配 `png` / `webp` 输出（`jpeg` 无 alpha
    通道，与透明互斥），不额外计费


    **认证方式**：在请求头中添加 `Authorization: Bearer YOUR_API_KEY`


    **获取 API Key**：访问 [API易控制台](https://api.apiyi.com/token) 创建令牌
  version: 1.0.0
servers:
  - url: https://api.apiyi.com
    description: 主要端点
security:
  - bearerAuth: []
paths:
  /v1/images/generations:
    post:
      tags:
        - 文生图
      summary: 文生图：根据文本描述生成图片
      description: >
        使用 `gpt-image-2.5-flare` / `gpt-image-2.5-sunburst` / `gpt-image-2`
        模型，根据文本提示词生成图片。


        - 必填：`model`、`prompt`

        -
        可选：`size`、`quality`、`output_format`、`output_compression`、`background`、`moderation`、`n`

        - 自定义尺寸需满足：最大边 ≤ 3840px、两边都是 16 的倍数、长短比 ≤ 3:1、总像素 0.65MP–8.3MP

        - 如需带参考图编辑或多图融合，请使用 [图片编辑接口](/api-capabilities/gpt-image-2/image-edit)
      operationId: generateGptImage2TextToImage
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TextToImageRequest'
            example:
              model: gpt-image-2.5-flare
              prompt: 赛博朋克城市雨夜，霓虹招牌特写，电影画幅
              size: 2048x1152
              quality: high
              output_format: jpeg
              output_compression: 85
      responses:
        '200':
          description: 成功生成图片
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ImageResponse'
        '400':
          description: 参数非法（size 不合约束、传了 input_fidelity、transparent 配 jpeg 等）
        '401':
          description: 未授权 - API Key 无效
        '403':
          description: 内容审核拦截
        '429':
          description: 请求频率超限或额度不足
        '500':
          description: 服务器内部错误
      security:
        - bearerAuth: []
components:
  schemas:
    TextToImageRequest:
      type: object
      required:
        - model
        - prompt
      properties:
        model:
          type: string
          description: >-
            模型名称。gpt-image-2.5-flare（速度优先）/ gpt-image-2.5-sunburst（画质与编辑精度优先）/
            gpt-image-2（上一代）三款同价同参数；生产环境可锁日期快照
          enum:
            - gpt-image-2.5-flare
            - gpt-image-2.5-sunburst
            - gpt-image-2
            - gpt-image-2.5-flare-2026-09-08
            - gpt-image-2.5-sunburst-2026-09-08
          default: gpt-image-2.5-flare
        prompt:
          type: string
          maxLength: 32000
          description: 提示词，支持中英文，最长 32,000 字符（原厂上限，按字符计）。建议把场景描述放在最前面
          example: 赛博朋克城市雨夜，霓虹招牌特写，电影画幅
        size:
          type: string
          description: >
            输出尺寸。预设值：1024x1024 / 1536x1024 / 1024x1536 / 2048x2048 / 2048x1152 /
            3840x2160 / 2160x3840。

            也可使用任意合法自定义尺寸（满足：最大边 ≤ 3840、两边 16 倍数、比例 ≤ 3:1、总像素 0.65–8.3MP）。
          example: 2048x1152
          default: auto
        quality:
          type: string
          description: >-
            画质档位。low（草图/批量）、medium（日常）、high（终稿/精细文字）、xhigh / max（2.5
            新增，更高画质、更高成本，gpt-image-2 不接受）、auto（默认）
          enum:
            - auto
            - low
            - medium
            - high
            - xhigh
            - max
          default: auto
        output_format:
          type: string
          description: 输出格式
          enum:
            - png
            - jpeg
            - webp
          default: png
        output_compression:
          type: integer
          description: 输出压缩率（0–100），仅 jpeg/webp 生效
          minimum: 0
          maximum: 100
          example: 85
        background:
          type: string
          description: >-
            背景模式。auto（默认）/ opaque / transparent。transparent 需配 png 或 webp（jpeg 无
            alpha 通道）
          enum:
            - auto
            - opaque
            - transparent
          default: auto
        moderation:
          type: string
          description: 审核强度。auto（默认）或 low（低强度）
          enum:
            - auto
            - low
          default: auto
        'n':
          type: integer
          description: 出图数量。本模型仅支持 1
          enum:
            - 1
          default: 1
    ImageResponse:
      type: object
      properties:
        created:
          type: integer
          description: Unix 时间戳
          example: 1776832476
        data:
          type: array
          description: 生成结果数组（本模型单次返回 1 张）
          items:
            type: object
            properties:
              b64_json:
                type: string
                description: >-
                  **纯 base64 字符串**（不含 data:image/...;base64, 前缀），客户端需自行 decode
                  写文件或拼前缀
                example: iVBORw0KGgoAAAANSUhEUgAA...
        usage:
          type: object
          description: 本次调用 token 用量（用于按 token 计费核算）
          properties:
            input_tokens:
              type: integer
              example: 42
            output_tokens:
              type: integer
              example: 6240
            total_tokens:
              type: integer
              example: 6282
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: 在 API易控制台获取的 API Key

````

This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.