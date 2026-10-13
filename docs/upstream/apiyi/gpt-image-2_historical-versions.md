> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# GPT-Image 历史版本

> GPT-Image 系列版本一览：现役 gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2 与历史版 GPT-Image-1 / 1-mini / 1.5 的模型 ID、发布时间、计费、端点，以及迁移到 2.5 / 2-All 的建议。

<Info>
  新项目请直接使用 [GPT-Image-2.5 / 2](/api-capabilities/gpt-image-2/overview)（官方版，文生图选 `gpt-image-2.5-flare`、改图选 `gpt-image-2.5-sunburst`）或 [GPT-Image-2-All](/api-capabilities/gpt-image-2-all/overview)（官逆，\$0.025/张统一价）。本页面仅保留历史版本的核心参数，方便老项目排查与迁移。
</Info>

## 版本一览

| 模型 ID | 发布时间 | 端点 | 价格特点 | 状态 |
| - | - | - | - | - |
| `gpt-image-2.5-flare`（快照 `-2026-09-08`） | 2026年9月 | `/v1/images/generations`、`/v1/images/edits` | 输入 \$5.00 / 输出 \$30.00 / 百万 tokens，与 gpt-image-2 相同 | **现役**，速度优先，文生图默认 |
| `gpt-image-2.5-sunburst`（快照 `-2026-09-08`） | 2026年9月 | `/v1/images/generations`、`/v1/images/edits` | 同上 | **现役**，画质与编辑精度优先 |
| `gpt-image-2`（快照 `-2026-04-21`） | 2026年4月 | `/v1/images/generations`、`/v1/images/edits` | 输入 \$5.00 / 输出 \$30.00 / 百万 tokens | 现役，上一代，`quality` 只到 `high` |
| `gpt-image-1.5` | 2025年12月 | `/v1/images/generations` | 输入 \$5.00 / 输出 \$10.00 / 百万 tokens | 仍可用，建议升级至 `gpt-image-2.5-flare` / `sunburst` |
| `gpt-image-1` | 2025年4月 | `/v1/images/generations`、`/v1/images/edits` | 输入 \$2.50 / 输出 \$8.00 / 百万 tokens | 仍可用，建议升级至 `gpt-image-2.5-flare` / `sunburst` |
| `gpt-image-1-mini` | 2025年4月 | `/v1/images/generations` | 较 `gpt-image-1` 更低 | 仍可用 |

<Tip>
  **直接迁移**：把 `model` 改成 `gpt-image-2` 或 `gpt-image-2-all` 即可，参数（`size` / `quality` / `output_format` 等）大体兼容，无需改代码结构。
</Tip>

## 通用参数（生图）

历史版本共享一套生图参数：

| 参数 | 说明 |
| - | - |
| `model` | `gpt-image-1.5` / `gpt-image-1` / `gpt-image-1-mini` |
| `prompt` | 图像描述，最长 32,000 字符（原厂上限，按字符计） |
| `size` | `1024x1024` / `1536x1024` / `1024x1536` / `auto` |
| `quality` | `low` / `medium` / `high` / `auto`（2.5 系列另有 `xhigh` / `max`） |
| `output_format` | `png`（默认） / `jpeg` / `webp` |
| `output_compression` | 仅 JPEG / WebP，0–100% |
| `background` | `transparent` / `opaque` / `auto` |
| `n` | 生成数量（1–10） |

## 快速调用示例

```python theme={null}
from openai import OpenAI

client = OpenAI(
    api_key="YOUR_API_KEY",
    base_url="https://api.apiyi.com/v1"
)

response = client.images.generate(
    model="gpt-image-1.5",  {/* 也可填 gpt-image-1 / gpt-image-1-mini */}
    prompt="A professional product photo on white background, soft studio lighting",
    size="1024x1024",
    quality="high"
)

print(response.data[0].url)
```

## 按图片计费参考

GPT-Image-1 / 1.5 同时支持 Token 计费与按图片计费，系统自动取较优方式：

### GPT-Image-1.5

| 质量 | 1024×1024 | 1024×1536 / 1536×1024 |
| - | - | - |
| Low | \$0.009 | \$0.013 |
| Medium | \$0.034 | \$0.050 |
| High | \$0.133 | \$0.200 |

### GPT-Image-1

| 质量 | 1024×1024 | 1024×1536 / 1536×1024 |
| - | - | - |
| Low | \$0.005 | \$0.006 |
| Medium | \$0.011 | \$0.015 |
| High | \$0.036 | \$0.052 |

<Warning>
  按图片计费仅作参考。实际扣费以系统自动选择的更优方式（Token 或按图片）为准。
</Warning>

## 图像编辑（仅 `gpt-image-1`）

`gpt-image-1` 支持基于遮罩的局部编辑端点 `/v1/images/edits`：

```python theme={null}
response = client.images.edit(
    model="gpt-image-1",
    image=open("original.png", "rb"),
    mask=open("mask.png", "rb"),
    prompt="A modern glass skyscraper with reflective windows",
    size="1024x1024"
)
```

| 参数 | 说明 |
| - | - |
| `image` | 原图，PNG/WebP/JPG，单张 \< 50MB，最多 16 张 |
| `mask` | 遮罩图，alpha=0 的区域将被重绘 |
| `prompt` | 描述要在遮罩区生成的内容 |

<Note>
  新项目编辑场景推荐使用 [GPT-Image-2 编辑](/api-capabilities/gpt-image-2/image-edit)（官方）或 [GPT-Image-2-All 编辑](/api-capabilities/gpt-image-2-all/image-edit)（官逆，最多 16 张多图融合）。
</Note>

## 迁移到 GPT-Image-2.5 / 2-All

| 你的诉求 | 推荐迁移到 |
| - | - |
| 沿用 OpenAI 官方端点、需要精确尺寸/质量控制 | `gpt-image-2.5-flare`（文生图）/ `gpt-image-2.5-sunburst`（编辑），官转 |
| 想要可预测的统一价（\$0.025/张）、提示词遵循更优 | `gpt-image-2-all`（官逆） |
| 仍需图像编辑 + 遮罩 | `gpt-image-2-all` 编辑端点（支持最多 16 张） |

<CardGroup cols={2}>
  <Card title="GPT-Image-2.5 / 2 概览" icon="bolt" href="/api-capabilities/gpt-image-2/overview">
    OpenAI 官方现役三款，端点 / 参数与历史版兼容
  </Card>

  <Card title="GPT-Image-2-All 概览" icon="sparkles" href="/api-capabilities/gpt-image-2-all/overview">
    官逆通道，\$0.025/张统一价，速度更快
  </Card>

  <Card title="官转 vs 官逆 对比" icon="scale" href="/api-capabilities/gpt-image-2/vs-gpt-image-2-all">
    一文看懂两者的差异和选型建议
  </Card>
</CardGroup>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.