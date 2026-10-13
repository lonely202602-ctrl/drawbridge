> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 原生工具出图

> API易 不建议用 OpenAI Responses API 的原生 image_generation 工具出图：按次固定收费、稳定性无法保证，请改用独立的 Images API 按量计费。本页仅供存量用户参考。

## 概述

<Warning>
  **API易 不建议用这条路出图，本页已从导航中隐藏，仅供存量用户参考。**

  Responses 原生 `image_generation` 工具在 API易 **只能按次计费**（每次出图固定收一笔约 \$0.20 的工具调用费，无法按实际用量计费），收费方式不合理；
  并且受资源供给限制，这条链路的**稳定性我们无法保证**。

  请改用独立的 Images API——[`/v1/images/generations`](/api-capabilities/gpt-image-2/text-to-image) 与 [`/v1/images/edits`](/api-capabilities/gpt-image-2/image-edit)，按量计费。
  需要「让 Agent 自己决定画不画」的效果，用对话模型判断意图后再调 Images API 即可，编排方式见
  [文本+出图能不能一个接口](/faq/text-and-image-in-one-api)。
</Warning>

除了独立的 [文生图](/api-capabilities/gpt-image-2/text-to-image) / [图片编辑](/api-capabilities/gpt-image-2/image-edit) 端点外，网关侧也能透传 **OpenAI Responses API 的原生 `image_generation` 工具**：由主线模型 `gpt-5.5` 在对话中自主决定何时画图，内部自动选用 GPT Image 模型，图片以 **base64** 形式回传在响应的 `output` 数组里。

<Note>
  **实测可用（2026-06-17）**：`gpt-5.5` + `POST /v1/responses` + `tools: [{"type": "image_generation"}]`，返回合法 base64 PNG。两条出图链路均走 OpenAI 官网直转。
</Note>

<Info>
  **怎么选？** 一律用独立端点 [`/v1/images/generations`](/api-capabilities/gpt-image-2/text-to-image)——只按实际用量计费，更划算、更可控、也是 API易 唯一保供的出图链路。本页的原生工具方式每次出图额外固定收一笔约 \$0.20 的工具调用费，且稳定性无法保证，**不建议新接入使用**。
</Info>

## 两种出图方式对比

| 对比项 | 原生工具方式（本页） | images API |
| - | - | - |
| **渠道来源** | OpenAI 官网直转 | OpenAI 官网直转 |
| **调用端点** | `/v1/responses` | `/v1/images/generations`、`/v1/images/edits` |
| **调用工具** | `image_generation` | 无（直接传 prompt） |
| **计费方式** | 按量计费 **+ 工具调用费** | 按量计费 |
| **计费明细** | 文本/图片输入输出费用官网同价；**工具调用费固定约 \$0.20/次** | 文本/图片输入输出费用官网同价 |
| **适合场景** | **API易 不建议使用**（按次固定收费、稳定性无法保证） | **全部出图场景**，计费更合理 |

> 根本差异：**原生工具方式每次出图额外固定约 \$0.20 工具费**；images API 只按实际用量计费，所以多数场景更划算。

## 最简调用

### cURL

```bash theme={null}
curl https://api.apiyi.com/v1/responses \
  -H "Authorization: Bearer $APIYI_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-5.5",
    "input": "Generate an image of a gray tabby cat hugging an otter with an orange scarf",
    "tools": [
      { "type": "image_generation" }
    ]
  }'
```

<Note>
  **指定 2.5 模型（2026-09-09 实测）**：工具对象支持 `model` 字段，传 `{"type": "image_generation", "model": "gpt-image-2.5-flare", "size": "1024x1024", "quality": "low"}` 可正常出图（HTTP 200、返回 1024×1024 PNG）。注意 `image_generation_call` 输出项**不回显模型名**，只回显 `quality` / `size` / `background` / `revised_prompt`，因此无法从响应侧确认实际用的是哪款 GPT Image 模型；对模型有硬要求时请直接走独立的 [文生图端点](/api-capabilities/gpt-image-2/text-to-image)。
</Note>

### Python（requests）

```python theme={null}
import base64, requests

resp = requests.post(
    "https://api.apiyi.com/v1/responses",
    headers={
        "Authorization": "Bearer $APIYI_KEY",
        "Content-Type": "application/json",
    },
    json={
        "model": "gpt-5.5",
        "input": "Generate an image of a gray tabby cat hugging an otter with an orange scarf",
        "tools": [{"type": "image_generation"}],
    },
    timeout=300,           # 出图慢，务必给足超时（单张约 60~90s）
)
data = resp.json()

# 从 output 数组里取出图片工具的结果
for item in data["output"]:
    if item.get("type") == "image_generation_call":
        raw = base64.b64decode(item["result"])   # result 字段是 base64 图
        with open("output.png", "wb") as f:
            f.write(raw)
        print("已保存 output.png,", len(raw), "bytes")
```

<Tip>
  可选参数放进 `tools` 项里：`{"type": "image_generation", "output_format": "png|jpeg|webp", "size": "1024x1024", ...}`。不写则用默认（png）。
</Tip>

## 响应结构（关键字段）

成功时 HTTP 200，响应体里：

```jsonc theme={null}
{
  "id": "resp_...",
  "model": "gpt-5.5-2026-04-23",
  "status": "completed",
  "output": [
    {
      "type": "image_generation_call",   // ← 关键：工具真的被触发了
      "result": "<很长的 base64 PNG 串>"  // ← 图片本体，base64，默认 png
    },
    { "type": "message", "content": [ /* 可能为空，出图时不一定带文字 */ ] }
  ],
  "usage": { "input_tokens": 2347, "output_tokens": 74 }
}
```

判断是否真出图：

* ✅ **成功**：`output` 里有 `type="image_generation_call"`，且 `result` 解码出 `\x89PNG` 开头的合法图。
* ⚠️ **被静默剥离**：HTTP 200 但 `output` 里**没有** `image_generation_call`，只有一段文字（常见于渠道不支持）。
* ❌ **报错**：非 200，或返回 `unknown tool` / `no available channels` 等。后两种可改走 `/v1/images/generations` 兜底。

## 透明背景

`image_generation` 工具支持 `background: "transparent"`，与 `/v1/images/generations` 一致：

```json theme={null}
{
  "model": "gpt-5.5",
  "input": "生成一个卡通狐狸贴纸",
  "tools": [{
    "type": "image_generation",
    "model": "gpt-image-2",
    "background": "transparent",
    "output_format": "png"
  }]
}
```

返回的 `image_generation_call` 会回显 `"background": "transparent"`，`result` 解出来是带 alpha 通道的 PNG。`output_format` 需为 `png` 或 `webp`，配 `jpeg` 会报错（jpeg 没有 alpha 通道）。

各模型的透明背景支持情况见 [怎么生成透明背景的图片](/faq/image-transparent-background)。

## 💰 计费说明

以一次真实调用为例（输入 2347 token、输出 74 token、生成 1 张 1122×1402 PNG），**最终扣费 = \$0.213954**，计费正确。拆解如下：

| 组成 | 配额计算 | 折合美金 |
| - | - | - |
| 文本部分 | `(输入 2347 + 输出 74×补全倍率 6) × 输入倍率 2.5` = **6977.5 配额** | ≈ \$0.014 |
| **图片工具部分** | **≈ 100,000 配额**（按张计，与 token 无关） | **≈ \$0.20 / 张** |
| **合计** | **106,977 配额** | **\$0.213954** |

> 换算关系：`500,000 配额 = \$1`（由 `106977 配额 = \$0.213954` 反推）。

<Warning>
  **控制台明细页的展示问题（需主动向客户说明）**

  在 APIYI 的「条件计费详情」页面里：

  * 上半部分只展示了**文本部分**的计算（`基础成本 = (2347 + 74×6) × 2.5 = 6977.50`）；
  * **画图工具调用的那一笔费用（≈100,000 配额 / ≈\$0.20）在明细列表里是一行空白，没有渲染出来**；
  * 但它**已经被正确计入**底部的「最终配额 106977 / \$0.213954」。

  **结论：计费是正常、准确的**——只是明细 UI 漏显示了「图片工具」这一行，导致明细各行相加 ≠ 最终总额。向客户解释时强调：**总额无误，差额就是这张图的工具费（约 \$0.20/张），只是没单独列出来。**
</Warning>

### 成本提示

* 出图费用是**按张固定**（约 \$0.20/张），不随 prompt 长短变化；文本 token 费相比之下很小。
* 单张耗时约 60\~90s，代码里超时建议设 ≥300s。
* 如果只是要图、不需要模型自主决策，用独立端点 [`/v1/images/generations`](/api-capabilities/gpt-image-2/text-to-image) 可能更省也更可控。

## 排错速查

| 现象 | 可能原因 | 处理 |
| - | - | - |
| 200 但无 `image_generation_call` | 当前渠道不支持该工具（静默剥离） | 换 key/渠道，或改用 `/v1/images/generations` |
| `no available channels` | 该 key 分组下无对应渠道 | 换有 GPT/图片渠道的 key 分组 |
| 请求超时 | 出图慢 | 客户端超时设 300s |
| `result` 解码后不是 PNG | 输出格式被改 / 渠道异常 | 检查 `output_format`，核对魔数 |

## 相关文档

* [GPT-Image-2 概览](/api-capabilities/gpt-image-2/overview) - 模型总览与定价
* [文生图 API 参考](/api-capabilities/gpt-image-2/text-to-image) - `/v1/images/generations`，多数场景的首选
* [图片编辑 API 参考](/api-capabilities/gpt-image-2/image-edit) - `/v1/images/edits`，参考图编辑 / 多图融合 / mask


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.