> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 图片编辑 API 参考

> gpt-image-2.5-sunburst / gpt-image-2.5-flare / gpt-image-2 图片编辑 API 参考与在线调试 — 上传参考图（最多 16 张）+ 指令进行单图改图、多图融合或 mask 局部重绘

<Info>
  右侧的交互式 Playground 支持直接上传本地图片。请在 **Authorization** 中填入你的 API Key（格式：`Bearer sk-xxx`），选择 image / mask 文件并填入 `prompt`、`model` 后一键发送即可。
</Info>

<Tip>
  **场景说明**：本页用于「基于一张或多张参考图改图 / 融合生成 / mask 局部重绘」。请求为 `multipart/form-data` 格式。如需纯文本生成图片，请使用 [文生图接口](/api-capabilities/gpt-image-2/text-to-image)。
</Tip>

<Warning>
  **🖥️ 浏览器 Playground 限制（重要）**

  本接口的响应包含**纯 base64 字符串**（数 MB 量级）。受浏览器渲染限制，右侧 Playground 在收到响应后**可能弹出** `请求时发生错误: unable to complete request` ——**实际请求已经成功**，只是浏览器无法把这么长的 base64 显示出来。

  **推荐做法**（小白零踩坑）：

  * **直接复制下方"代码示例"中的 Python / Node.js / cURL 到本地运行**，代码会自动 `base64.b64decode` 并把图片**保存为本地文件**。
  * 如要在浏览器里试 Playground，**用极小的参考图（\< 50KB）** 并把 `size` 设为最小档（如 `1024x1024`）、`quality` 改为 `low`。
</Warning>

<Warning>
  **⚠️ 关键差异（从 gpt-image-1.5 迁移注意）**

  * **不要传 `input_fidelity`** —— 三款模型均强制启用高保真，传了会 400 报错（2.5 实测 2026-09-09 同样 400）
  * **编辑请求的输入 token 明显更高** —— 因为参考图按 Vision 计费规则换算成大量 token，预算要留足
  * **多图融合最多 16 张** —— `image[]` 字段重复传入，超过会报错
</Warning>

<Warning>
  **📎 多图融合顺序有意义**

  `image[]` 字段可重复传入多张参考图，**顺序将作为 prompt 中「图1/图2/图3」的引用依据**。建议在 prompt 中显式指代，例如：

  > 把图1的人物放进图2的场景，沿用图3的色彩风格

  单张文件上限 **50MB**（multipart 文件上传），格式 `png` / `jpg` / `webp`；实践中建议先压到 **1.5MB 以内**再上传（详见下方「上传大小限制速查」）。
</Warning>

## 代码示例

### Python（OpenAI SDK · 单图编辑）

```python theme={null}
from openai import OpenAI
import base64

client = OpenAI(
    api_key="sk-your-api-key",
    base_url="https://api.apiyi.com/v1"
)

resp = client.images.edit(
    model="gpt-image-2.5-sunburst",
    image=open("photo.png", "rb"),
    prompt="把背景换成海边黄昏，保留人物细节",
    size="1536x1024",
    quality="high"
)

# b64_json 是纯 base64（无前缀），需自己 decode
with open("edited.png", "wb") as f:
    f.write(base64.b64decode(resp.data[0].b64_json))
```

### Python（OpenAI SDK · 多图融合）

```python theme={null}
resp = client.images.edit(
    model="gpt-image-2.5-sunburst",
    image=[
        open("person.png", "rb"),
        open("scene.png", "rb"),
        open("style.png", "rb"),
    ],
    prompt="把图1人物放进图2场景，沿用图3的色彩风格，保持光线一致",
    size="1536x1024",
    quality="high"
)

with open("fused.png", "wb") as f:
    f.write(base64.b64decode(resp.data[0].b64_json))
```

### cURL（多图融合）

```bash theme={null}
curl -X POST "https://api.apiyi.com/v1/images/edits" \
  -H "Authorization: Bearer sk-your-api-key" \
  -F "model=gpt-image-2.5-sunburst" \
  -F "prompt=把图1的人物放进图2的场景，保留图3的色彩风格" \
  -F "size=1536x1024" \
  -F "quality=high" \
  -F "image[]=@person.png" \
  -F "image[]=@scene.png" \
  -F "image[]=@style.png"
```

### cURL（mask 局部重绘）

```bash theme={null}
curl -X POST "https://api.apiyi.com/v1/images/edits" \
  -H "Authorization: Bearer sk-your-api-key" \
  -F "model=gpt-image-2.5-sunburst" \
  -F "prompt=把天空换成粉色晚霞" \
  -F "size=1024x1024" \
  -F "quality=high" \
  -F "image[]=@photo.png" \
  -F "mask=@mask.png" \
  | jq -r '.data[0].b64_json' | base64 -d > photo_edited.png
```

### Node.js（原生 fetch + FormData · 多图融合）

```javascript theme={null}
import fs from 'node:fs';

const form = new FormData();
form.append('model', 'gpt-image-2.5-sunburst');
form.append('prompt', '把图1的人物放进图2的场景');
form.append('size', '1536x1024');
form.append('quality', 'high');
form.append('image[]', new Blob([fs.readFileSync('./person.png')]), 'person.png');
form.append('image[]', new Blob([fs.readFileSync('./scene.png')]), 'scene.png');

const resp = await fetch('https://api.apiyi.com/v1/images/edits', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer sk-your-api-key' },
    body: form
});

const { data } = await resp.json();
fs.writeFileSync('fused.png', Buffer.from(data[0].b64_json, 'base64'));
```

## 参数说明速查

| 字段 | 类型 | 必填 | 默认 | 说明 |
| - | - | - | - | - |
| `model` | text | 是 | — | `gpt-image-2.5-sunburst`（编辑精度优先，改图首选）/ `gpt-image-2.5-flare`（速度优先）/ `gpt-image-2`（上一代，仍可用）；生产环境可锁日期快照 `gpt-image-2.5-sunburst-2026-09-08` / `gpt-image-2.5-flare-2026-09-08`。三款同价同参数 |
| `prompt` | text | 是 | — | 编辑 / 融合指令 |
| `image[]` | file | 是 | — | 参考图，可重复多次（**最多 16 张**） |
| `mask` | file | 否 | — | 掩码图（仅对第一张 image 生效，要求带 alpha 通道） |
| `size` | text | 否 | `auto` | 输出尺寸，同文生图 |
| `quality` | text | 否 | `auto` | `low` / `medium` / `high` / `xhigh` / `max` / `auto`；`xhigh` / `max` 是 2.5 新增档位，`gpt-image-2` 只到 `high` |
| `output_format` | text | 否 | `png` | `png` / `jpeg` / `webp` |
| `output_compression` | text | 否 | — | 0–100，仅 `jpeg` / `webp` 生效 |
| `background` | text | 否 | `auto` | `transparent` / `opaque` / `auto`；传 `transparent` 时 `output_format` 必须是 `png` 或 `webp`。注意编辑接口的透明是**重绘去背**而非精确抠像，见 [透明背景 FAQ](/faq/image-transparent-background) |

<Warning>
  **`quality` 不要传旧版 DALL·E 的 `standard` / `hd`。** 只接受 `low` / `medium` / `high` / `xhigh` / `max` / `auto` 六个官方枚举值（`xhigh` / `max` 仅 2.5 两款接受）。旧值在不同后端渠道下行为不一致：有时直接 400 报错（`invalid_value`），有时被静默忽略、按 `auto` 档跑出结果（费用不可控）。请始终显式传官方枚举值之一。
</Warning>

## 上传大小限制速查

| 项目 | 限制 | 说明 |
| - | - | - |
| 参考图数量 | 最多 **16 张** | `image[]` 字段重复传入 |
| 单张图片（multipart 文件上传） | 每张**小于 50MB** | 支持 `png` / `jpg` / `webp` |
| 单张图片（base64 data URL 方式） | 字段长度约 **20MiB** | 这是 URL/base64 **字符串字段**的长度限制（schema `maxLength: 20971520`），**不等同于** multipart 文件上传的 50MB 上限；base64 编码会膨胀约 1/3，**实际原图建议控制在 15MB 以内** |
| mask 文件 | **PNG 且小于 4MB** | 须与原图相同尺寸、带 alpha 通道 |

<Warning>
  **总请求体积别顶满**：虽然单图上限 50MB、最多 16 张，但多张大图同时接近上限时，单个请求体会非常大，容易在网关 / CDN / 超时层面失败。实践中建议**每张先压到 1.5MB 以内**（JPEG 质量 80-90），成功率和出图速度都会明显更好——输出画质与输入图体积无关。
</Warning>

## 参考图格式要求与预处理

`/v1/images/edits` 只接受 **png / jpg / webp** 三种标准格式。如果收到下面这个 400：

```json theme={null}
{
  "error": {
    "message": "Invalid image file or mode for image 1, please check your image file. ...",
    "type": "shell_api_error",
    "code": "invalid_image_file"
  }
}
```

大概率是参考图**并非标准 JPEG/PNG**。最常见的坑是手机原拍照片的 **MPO 格式**（Multi-Picture Object，多帧 JPEG 容器）：华为 Mate 系列等机型直出的 `.jpg` 内嵌 HDR 增益图副帧，实为 MPO。这类文件的文件头同为 `FFD8`，**扩展名和 `file` 命令都显示 JPEG**，肉眼无法分辨，只有按帧解析（如 Pillow）才能识别。报错里的「image 1」指第 N 张参考图（序号从 1 开始），可按序号定位问题图。

<Info>
  **2026-07 实测**：MPO 图 5 次上传全部 400；同一批图重编码为标准 JPEG/PNG 后，**保持 3072×4096 原分辨率**上传全部成功——问题出在格式，不在尺寸/体积。该错误在入口校验阶段快速返回（约 4 秒），**不计费**。
</Info>

**判别与修复**：`Image.open(f).format` 返回 `"MPO"` 即需转换。上传链路统一做一次重编码即可，顺带兼容 HEIC 等其它手机格式：

```python theme={null}
from PIL import Image
import io

def normalize_image(path: str) -> bytes:
    """手机图片（MPO/HDR 多帧等）转标准 JPEG，通过 edits 格式校验"""
    im = Image.open(path)
    im.load()                      # MPO 只取第一帧（全尺寸主图）
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGB")
    out = io.BytesIO()
    im.save(out, format="JPEG", quality=92)   # 或 format="PNG"
    return out.getvalue()
```

<Tip>
  业务是「用户上传实拍图」的场景（家装效果图、商品实拍等），建议在服务端上传链路**统一重编码**，而不是逐张排查——手机 HDR 照片会持续出现。更多输入图片处理技巧见 [图片 API 调用须知与最佳实践](/api-capabilities/image-api-best-practices)。
</Tip>

## mask 局部重绘要求

* 与原图**相同尺寸**，**PNG 格式**，单张**小于 4MB**
* **必须带 alpha 通道**：透明区域（alpha=0）= 要重绘的部分，不透明区域 = 保留
* mask 仅对**第一张** image 生效
* mask 作为「软引导」而非精确边界，模型可能在蒙版周围扩展 / 收敛

<Tip>
  **多轮迭代**：把上一次的输出作为下一次的 `image[]` 输入，配合新的编辑指令，可逐步精调画面。每一轮都按 token 实计，预算时留意累计成本。
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
        "input_tokens": 848,
        "input_tokens_details": {
            "image_tokens": 832,
            "text_tokens": 16
        },
        "output_tokens": 196,
        "output_tokens_details": {
            "image_tokens": 196,
            "text_tokens": 0
        },
        "total_tokens": 1044
    }
}
```

<Warning>
  `b64_json` 字段是**纯 base64**，**不含** `data:image/...;base64,` 前缀，与 `gpt-image-2-all` 不同。客户端需自行 decode 写文件，或在浏览器端拼前缀渲染。
</Warning>

<Info>
  编辑请求的 `input_tokens` 通常**显著高于**同尺寸文生图，原因是参考图按 Vision 计费规则换算——具体消耗多少可以直接读 `usage.input_tokens_details.image_tokens`，与文本部分（`text_tokens`）是分开计的。多图融合时 `image_tokens` 会随参考图数量**严格线性增加**（2026-07 实测：4 张 1024² = 4 × 1024 tokens），量化数据见 [多图输入的价格影响](/api-capabilities/gpt-image-2/overview#多图输入的价格影响2026-07-实测)。详细字段说明见 [概览页「如何查看每次调用的真实 token 数」](/api-capabilities/gpt-image-2/overview#如何查看每次调用的真实-token-数)。
</Info>


## OpenAPI

````yaml api-reference/gpt-image-2-edit-openapi.yaml POST /v1/images/edits
openapi: 3.1.0
info:
  title: gpt-image-2.5 / 2 图片编辑 API
  description: >
    OpenAI GPT-Image 2.5 / 2 系列（`gpt-image-2.5-sunburst` / `gpt-image-2.5-flare`
    / `gpt-image-2`）— 图片编辑接口。三款同价同参数，sunburst 编辑精度最高。


    - 支持单图编辑、多图融合（最多 16 张参考图）、mask 局部重绘

    - 请求格式为 `multipart/form-data`

    - 在 prompt 中可用「图1/图2/图3」指代 `image` 上传顺序

    - **参考图自动启用高保真**，**不要**传 `input_fidelity`（传了会报错）

    - 编辑请求的输入 token 会比同尺寸文生图明显更高


    **认证方式**：在请求头中添加 `Authorization: Bearer YOUR_API_KEY`


    **获取 API Key**：访问 [API易控制台](https://api.apiyi.com/token) 创建令牌
  version: 1.0.0
servers:
  - url: https://api.apiyi.com
    description: 主要端点
security:
  - bearerAuth: []
paths:
  /v1/images/edits:
    post:
      tags:
        - 图片编辑
      summary: 图片编辑：根据指令编辑或融合参考图
      description: >
        使用 `gpt-image-2.5-sunburst` / `gpt-image-2.5-flare` / `gpt-image-2`
        模型，根据文本指令对输入图片进行编辑、多图融合或 mask 局部重绘。


        - 必须提供至少一张 `image`（最多 16 张）

        - multipart 文件上传单张图片小于 50MB，格式 png/jpg/webp（base64 data URL 方式受约 20MiB
        字段长度限制，原图建议 ≤ 15MB）

        - `mask` 字段（可选）需与原图相同尺寸，PNG 格式且小于 4MB，必须带 alpha 通道（透明 = 重绘区域）

        - mask 仅对第一张 image 生效

        - 不要传 `input_fidelity`（强制高保真，传了会报错）
      operationId: editGptImage2Image
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              $ref: '#/components/schemas/EditImageRequest'
            encoding:
              image:
                contentType: image/png, image/jpeg, image/webp
              mask:
                contentType: image/png
      responses:
        '200':
          description: 成功生成图片
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ImageResponse'
        '400':
          description: 参数非法（含 input_fidelity / transparent 配 jpeg / size 不合约束等）
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
    EditImageRequest:
      type: object
      required:
        - model
        - prompt
        - image
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
          default: gpt-image-2.5-sunburst
        prompt:
          type: string
          maxLength: 32000
          description: 编辑/融合指令，最长 32,000 字符（原厂上限，按字符计）。多图场景可用「图1/图2/图3」指代 image 上传顺序
          example: 把图1的人物放进图2的场景，沿用图3的色彩风格
        image:
          type: array
          description: >-
            参考图，可重复多次（**最多 16 张**）。**单图直接传一次，多图重复传同名 `image` 字段**（例如 `-F
            image=@a.png -F image=@b.png`），按上传顺序对应 prompt
            中的「图1/图2/...」。multipart 文件上传单张小于 50MB，格式 png/jpg/webp；实践建议压到 1.5MB
            以内
          items:
            type: string
            format: binary
        mask:
          type: string
          format: binary
          description: |
            掩码图（可选，仅对第一张 image 生效）。要求：
            - 与原图相同尺寸
            - PNG 格式且小于 4MB
            - 必须带 alpha 通道（alpha=0 表示要重绘的区域，不透明区域保留）
        size:
          type: string
          description: 输出尺寸（同文生图）。预设或满足约束的自定义尺寸
          example: 1536x1024
          default: auto
        quality:
          type: string
          description: 画质档位。xhigh / max 为 2.5 新增，gpt-image-2 不接受
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
    ImageResponse:
      type: object
      properties:
        created:
          type: integer
          example: 1776832476
        data:
          type: array
          description: 生成结果数组（本模型单次返回 1 张）
          items:
            type: object
            properties:
              b64_json:
                type: string
                description: '**纯 base64 字符串**（不含 data:image/...;base64, 前缀）'
                example: iVBORw0KGgoAAAANSUhEUgAA...
        usage:
          type: object
          description: 本次调用 token 用量
          properties:
            input_tokens:
              type: integer
              example: 1280
            output_tokens:
              type: integer
              example: 6240
            total_tokens:
              type: integer
              example: 7520
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: 在 API易控制台获取的 API Key

````

This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.