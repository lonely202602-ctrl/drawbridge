> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# GPT-image-2.5 / 2 官转 vs 官逆 对比

> 官转 gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2 与官逆姐妹模型 gpt-image-2.5-all / gpt-image-2-all / gpt-image-2.5-vip / gpt-image-2-vip 对比：性质、计费、端点、上传/输出格式、速度与画质定位、指令遵循等差异，帮你选对模型。

## 一句话结论

| 你需要 | 选这个 |
| - | - |
| **`quality` 画质参数 / mask 局部重绘 / 任意自定义尺寸（不限 30 档） / OpenAI 官方完全对齐字段** | 官转按量计费：`gpt-image-2.5-flare`（速度优先）/ `gpt-image-2.5-sunburst`（编辑精度优先）/ `gpt-image-2`（上一代） |
| **可预测的统一价（\$0.025/张）+ 出图快且稳（快就是优势）** | `gpt-image-2-all` / `gpt-image-2.5-all`（官逆，ChatGPT 网页线，\~90s；2.5-all 背后是 Images 2.5） |
| **可预测的统一价 + 锁尺寸（30 档含 4K）** | `gpt-image-2-vip` / `gpt-image-2.5-flare-vip` / `gpt-image-2.5-sunburst-vip`（官逆，Adobe 线；2.5 两款 `quality` 六档全开，`gpt-image-2-vip` 到 `high`） |

八个模型**底层都是 OpenAI GPT-Image 2.5 / 2 系列**，差别在通道性质（官方直连 vs 逆向）、计费方式、参数粒度。官转三款同价同参数，官逆五款同套调用代码（`-all` 两款 \$0.025/张、`-vip` 三款 \$0.03/张）。

<Note>
  **官逆三线（-all / 2.5-all / -vip 三款）**：本页"官逆"列同时覆盖 **`gpt-image-2-all`**、**`gpt-image-2.5-all`** 与 **`gpt-image-2-vip` / `gpt-image-2.5-flare-vip` / `gpt-image-2.5-sunburst-vip`**（别名 `gpt-image-2.5-vip` = sunburst-vip）。同套调用代码（`-vip` 三款额外支持 `size`），按次计费：`-all` 两款 \$0.025/张、`-vip` 三款 \$0.03/张：

  * `gpt-image-2-all` / `gpt-image-2.5-all`：ChatGPT 网页线，**约 90 秒** 出图——**快就是优势**；2.5-all 背后是 Images 2.5
  * `gpt-image-2-vip` 与 2.5 两款 -vip：Adobe 线（Firefly），**支持 `size` 锁尺寸（30 档含 4K）**；三款都接受 `quality`（属渠道行为不承诺）：2.5 两款 2026-09-10 复测 `xhigh` / `max` 也已放开、六档全开，`gpt-image-2-vip` 到 `high`；都能出透明背景；flare-vip 最快、画面偏软，sunburst-vip 与 `gpt-image-2-vip` 画质接近
  * 共同点：都不支持 `n`；`mask` 都只是整图重绘，不保证只改蒙版区

  需要精确的 mask 局部重绘、30 档以外的任意自定义尺寸或 `n` 多图时，走官转（`gpt-image-2.5-flare` / `gpt-image-2.5-sunburst` / `gpt-image-2`）。
</Note>

<Tip>
  **关于速度**：当前 `-all` / `-vip` 出图速度**比刚上线时慢一些**，这是 **OpenAI 官方算力波动** 导致的全链路放缓——APIYI 的号池和运维侧并无问题，所有逆向通道用户都会感受到。建议把超时设置在 300 秒以上，复杂场景预留更多。
</Tip>

## 完整对比表

| 维度 | **gpt-image-2-all / 2.5-all / -vip**（官逆，高性价比） | **gpt-image-2.5-flare / sunburst / gpt-image-2**（官转，正式版） |
| - | - | - |
| **模型名** | `gpt-image-2-all`（出图最快） / `gpt-image-2.5-all`（同线路的 2.5 版） / `gpt-image-2-vip`（画质优先、可锁尺寸） / `gpt-image-2.5-flare-vip` · `gpt-image-2.5-sunburst-vip`（同线路的 2.5 版，别名 `gpt-image-2.5-vip`） | `gpt-image-2.5-flare`（速度优先） / `gpt-image-2.5-sunburst`（编辑精度优先） / `gpt-image-2`（上一代） |
| **通道性质** | `-all` / `2.5-all`：逆向 ChatGPT 官网线路<br />`-vip` 三款：Adobe 官逆线路（Firefly，高质量 GPT-Image 2.5 逆向资源，非超分） | 官方直连（OpenAI Images API），三款同价同参数 |
| **计费方式** | **按次计费**：`-all` 两款固定 \$0.025/次、`-vip` 三款固定 \$0.03/次 | **按量计费**：按 token 实计，官网同价；本站充值加赠后约 **8.5 折** |
| **典型成本/张** | \$0.025（`-all`）/ \$0.03（`-vip`），不区分尺寸 / 画质 | 实测 **\$0.03 – \$0.2**（与提示词长度、size、quality 正相关） |
| **令牌分组** | 默认分组（Default） | 默认分组（Default） |
| **令牌类型** | **按次计费** 或 **按量优先** 均可 | **仅支持按量优先**（本模型按 token 计费，按次计费令牌不可用） |
| **推荐端点** | **`/v1/images/generations` + `/v1/images/edits`**（更稳定、上游供给更足，且同套代码兼容官转，风控异常时换 `model` 名即可切换） | `/v1/images/generations` + `/v1/images/edits` |
| **上传图片格式** | multipart file（edits 端点） | multipart file（编辑接口） |
| **输出图片格式** | `b64_json`（默认，**纯 base64 无前缀**，2026-07 实测；历史版本曾带前缀）或 `url`（R2 CDN） | `b64_json`（**纯 base64，无前缀**） |
| **上传图片数（编辑）** | 多张 | **最多 16 张**（`image[]`） |
| **mask 局部重绘** | `-all` / `2.5-all`：❌ 不支持<br />`-vip` 三款：⚠️ 接受但整图重绘，真实照片三次实测蒙版内外改动比 ≈1（2026-09-09），不保证只改蒙版区 | ✅ 支持（要求带 alpha 通道） |
| **指令遵循** | 好 | **优秀** |
| **生成速度** | `-all`：约 **90 秒**（快就是优势）<br />`gpt-image-2-vip`：约 **90–150 秒**<br />2.5 两款 -vip：1024² 串行实测 flare 22～138 秒、sunburst 37～120 秒，波动大；RPM 500 以内无需考虑并发，偶发 429 退避重试或看 [实时动态](/live)<br />📌 当前比刚上线时慢——OpenAI 官方算力波动所致，非 APIYI 侧问题 | `gpt-image-2.5-flare`：官转最快，1K `low` 实测约 10 秒（2026-09-09）<br />`gpt-image-2.5-sunburst`：1K `low` 实测约 14 秒，高画质更慢<br />`gpt-image-2`：约 **100-120 秒**，复杂场景 + 4K 可达 3-5 分钟 |
| **画质倾向** | `-all` / `2.5-all`：好（同一线路，两个名字出的是同一批图）<br />`-vip`：同尺寸 6 组提示词人眼对比，sunburst-vip 与 `gpt-image-2-vip` 接近，flare-vip 偏软、装饰细节少；三款中文标题笔画都正确 | 稳定；2.5 两款高于 gpt-image-2，sunburst 最高，且可用 `quality=xhigh` / `max` 拉满 |
| **`size` 参数** | `-all` / `2.5-all`：❌ 不接受（写进 prompt）<br />`-vip` 三款：✅ **已恢复**（2026-07-22 起），支持 30 档常见尺寸（含 4K）；仅 images 端点生效，chat 端点不支持 | ✅ 任意合法尺寸 |
| **`size = auto` 行为** | `-all`：— 不接受 `size` 字段<br />`-vip`：不传 `size` 时 `gpt-image-2-vip` / sunburst-vip 出 2048×2048，flare-vip 固定出 1024×1536；默认值随上游变动过，要锁尺寸请显式传 30 档之一 | ✅ 默认值，OpenAI 官方语义"按 prompt 智能选"；**社区实测偏向 1:1 方形（1024×1024）**，要其它比例请显式传 `size` |
| **支持 4K** | `-all` / `2.5-all`：❌<br />`-vip` 三款：✅ 4K Detail 档（如 `3840x2160` / `2880x2880`），不加价 | ✅ 含 `3840×2160` |
| **常见输出尺寸** | `-all`：16:9 → 1672×941、9:16 → 941×1672、1:1 → 1254×1254（自适应）<br />`-vip` 三款：30 档（10 比例 × 1K/2K/4K），见 [30 档完整对照表](/api-capabilities/gpt-image-2-vip/overview#支持的-size30-档完整对照表)；表外尺寸不报错但会被对齐到 16 倍数或抬到最小边 | 8 个预设 + 任意合法自定义尺寸 |
| **画质参数 `quality`** | `-all` / `2.5-all`：❌ 不支持（不要传）<br />`-vip` 三款：✅ 实测生效，属渠道行为不承诺——2.5 两款 `auto` / `low` / `medium` / `high` / `xhigh` / `max` 六档全开（`xhigh` / `max` 2026-09-10 放开），`gpt-image-2-vip` 到 `high` | ✅ `low` / `medium` / `high` / `xhigh` / `max` / `auto`（`xhigh` / `max` 仅 2.5 两款） |
| **`quality` 档位对齐** | 2048×1152 输出 token：`gpt-image-2-vip` low 157 / medium 1,413 / high 5,650；2.5 两款 -vip low 157 / medium 367 / high 1,413 / xhigh 2,511 / max 5,650——**2.5 的 `high` = `gpt-image-2-vip` 的 `medium`，2.5 的 `max` = `gpt-image-2-vip` 的 `high`**，与官转 2.5 对 gpt-image-2 的关系一致；按次计费，档位不影响价格 | 1024² 输出 token：官转 2.5 low 196 / medium 439 / high 1,756 / xhigh 3,122 / max 7,024；`gpt-image-2` low 196 / medium 1,756 / high 7,024——官转 2.5 的 `high` = `gpt-image-2` 的 `medium`；按 token 计费 |
| **`n` 参数** | ❌ 官逆五款均不支持（单次仅返回 1 张） | ✅ 支持 |
| **透明背景** | `-all` / `2.5-all`：⚠️ 无 `background` 参数，只能在提示词里要求，偶现不稳定<br />`-vip` 三款：✅ `background: "transparent"` 实测返回带 alpha 的 PNG（不承诺） | ✅ 参数控制稳定 —— `background: "transparent"` + `png` / `webp` |
| **中文提示词** | ✅ 原生 | ✅ 原生 |
| **文字渲染** | 高还原度 | 高还原度（`high` 档位最强） |
| **API 文档** | [GPT-Image-2.5-All 概览](/api-capabilities/gpt-image-2-all/overview) / [GPT-Image-2.5-VIP 概览](/api-capabilities/gpt-image-2-vip/overview) | [GPT-Image-2.5 / 2 概览](/api-capabilities/gpt-image-2/overview) |

<Info>
  🔑 **如何创建或管理令牌**：[https://api.apiyi.com/token](https://api.apiyi.com/token)\
  在控制台创建令牌时可以选择分组（`Default` 默认即可）和令牌类型（**按次计费** / **按量优先**）。**调用官转三款（`gpt-image-2.5-flare` / `sunburst` / `gpt-image-2`）必须使用「按量优先」类型的令牌**，否则会因计费方式不匹配被拒。
</Info>

## 选型场景

### 选 `gpt-image-2-all` / `gpt-image-2.5-all`（官逆）的场景

<CardGroup cols={2}>
  <Card title="💰 成本可预测" icon="dollar-sign">
    单价稳定 \$0.025/张（2026-10-10 起由 \$0.03 降价），无尺寸 / 画质阶梯，叠加充值加赠成本更低，**适合大批量生产、成本必须封顶的场景**（信息图、营销物料、电商素材批量）。
  </Card>

  <Card title="⚡ 出图速度较快" icon="bolt">
    约 90 秒出图，**比 `-vip` 和官转都略快**，前端实时交互体验更好。
  </Card>

  <Card title="🔁 一套代码随时互切" icon="repeat">
    Images API 标准格式，与 `-vip` 三款和官转三款 **同套代码**——改个 `model` 名即可互切或兜底。`gpt-image-2-all` 与 `gpt-image-2.5-all` 同价同行为，新名只是把 ChatGPT 网页版升级到 Images 2.5 这件事表达在模型名上。
  </Card>

  <Card title="🌏 中文 + 营销文字" icon="type">
    中文提示词原生友好、招牌 / 海报 / 信息图文字还原度高，**适合面向中文用户的内容生产**。
  </Card>
</CardGroup>

### 选 `-vip` 三款（官逆，锁尺寸）的场景

<CardGroup cols={2}>
  <Card title="🎚️ 可传 quality（2.5 两款六档全开）" icon="sliders-horizontal">
    `gpt-image-2.5-flare-vip` / `gpt-image-2.5-sunburst-vip` 实测接受 `auto` 到 `max` 六档（`xhigh` / `max` 2026-09-10 放开），`gpt-image-2-vip` 到 `high`，都属渠道行为不承诺。档位对齐：2.5 的 `high` 只等于 `gpt-image-2-vip` 的 `medium`，2.5 的 `max` 才等于 `gpt-image-2-vip` 的 `high`；按次 \$0.03 不随档位变。
  </Card>

  <Card title="⏱️ 三款怎么挑" icon="hourglass">
    flare-vip 最快、画面偏软；sunburst-vip 画质与编辑精度更高、人眼与 `gpt-image-2-vip` 接近；三款最高档 token 相同（2.5 传 `max`、`gpt-image-2-vip` 传 `high`）。都比 `-all` 慢，`max` 档 1024² 实测 80～160 秒，接受更长等待时选。
  </Card>

  <Card title="🖼️ 锁尺寸 / 4K" icon="expand">
    `size` 参数**已恢复**（2026-07-22 起）：支持 30 档常见尺寸（10 比例 × 1K/2K/4K），电商主图、海报模板、4K 壁纸可严格输出，统一 \$0.03/张、4K 不加价。
  </Card>

  <Card title="🔁 与 -all 共用代码" icon="copy">
    调用结构与 `-all` 一致（仅多一个 `size` 字段）——一套代码五个官逆模型来回切，随时按速度 / 画质偏好换 `model` 名。
  </Card>
</CardGroup>

<Note>
  `-vip` 的 `size` 仅在 `/v1/images/generations` 与 `/v1/images/edits` 端点生效，**`/v1/chat/completions` 对话端点不支持 `size`**。需要 30 档以外的任意自定义尺寸或精确的 mask 局部重绘时走官转（`gpt-image-2.5-flare` / `sunburst`）。该参数可用性随上游变动，最新状态以 [实时动态](/live) 为准。
</Note>

### 选官转（`gpt-image-2.5-flare` / `sunburst` / `gpt-image-2`）的场景

<CardGroup cols={2}>
  <Card title="🎚️ 需要画质档位" icon="sliders-horizontal">
    `quality` 六档全开且**官方承诺**，档位与 token 量按官方规格稳定；官逆 -vip 2.5 两款虽然也已放开六档（2026-09-10 实测），但属渠道行为不承诺，`gpt-image-2-vip` 仍只到 `high`。
  </Card>

  <Card title="🎯 mask 局部重绘" icon="paintbrush">
    支持 alpha 通道蒙版，**精准修改图片局部区域而保留其余部分**——官逆各款只做整图重绘，不保证只改蒙版区。
  </Card>

  <Card title="🖼️ 任意自定义尺寸" icon="expand">
    `size` 参数接受**任意合法尺寸**（含 4K），不限预设档位。`-vip` 三款只保证 30 档，表外尺寸会被改写，**要严格的自定义尺寸走官转**。
  </Card>

  <Card title="🔌 与 OpenAI 官方一致" icon="plug">
    走官方 Images API，字段与行为完全与官方一致。**已有基于 OpenAI 官方 SDK 的代码 / 系统**可零改动迁移，长期更稳。
  </Card>
</CardGroup>

## 关键差异详解

### 1. `b64_json` 格式差异（迁移坑！）

2026-07 实测官转与官逆各款都返回**纯 base64（无 `data:` 前缀）**，但 `gpt-image-2-all` 历史版本曾直接带前缀——跨模型 / 跨版本复用代码时，统一做前缀检测最稳：

```python theme={null}
# 通用写法：先检测前缀再处理，官转三款与官逆五款均适用
b64 = resp["data"][0]["b64_json"]
if b64.startswith("data:"):          # 兼容曾出现过的带前缀响应
    b64 = b64.split(",", 1)[1]
with open("out.png", "wb") as f:
    f.write(base64.b64decode(b64))   # ✅ 写文件
img_tag = f'<img src="data:image/png;base64,{b64}">'  # ✅ 浏览器渲染
```

<Warning>
  从一个切到另一个时，**`b64_json` 处理代码必须改**，否则会拿到损坏的 data URL 或 decode 失败。
</Warning>

### 2. 分辨率控制方式

**`gpt-image-2-all` / `gpt-image-2.5-all`**（不接受 `size`，画幅写在 prompt 里，两个名字行为相同）：

```
"横版 16:9 电影画幅，黄昏时的海边老灯塔"   → 输出约 1672×941
"竖版 9:16 手机壁纸，赛博朋克城市雨夜"      → 输出约 941×1672
"1024×1024 方形 LOGO，极简猫咪线条"          → 输出约 1254×1254
```

**`-vip` 三款**（`gpt-image-2.5-vip` / `gpt-image-2.5-flare-vip` / `gpt-image-2-vip`，`size` 已恢复，2026-07-22 起）：

支持 **30 档常见尺寸**（10 比例 × 1K/2K/4K），请求体直接传 `size: "宽x高"`（须为 30 档之一，完整清单见 [30 档对照表](/api-capabilities/gpt-image-2-vip/overview#支持的-size30-档完整对照表)）。三款实测也都接受 `quality`（属渠道行为不承诺）：2.5 两款六档全开（`xhigh` / `max` 2026-09-10 放开），`gpt-image-2-vip` 到 `high`；2.5 两款的 `high` 输出 token 只等于 `gpt-image-2-vip` 的 `medium`，`max` 才等于它的 `high`：

```python theme={null}
client.images.generate(
    model="gpt-image-2.5-vip",   # 别名 = gpt-image-2.5-sunburst-vip；换 gpt-image-2.5-flare-vip / gpt-image-2-vip 只改这一行
    prompt="...",
    size="3840x2160",   # ✅ 30 档之一，仅 images 端点生效（chat 端点不支持）
    quality="max"       # 可选；2.5 两款六档全开，gpt-image-2-vip 只到 high；按次 $0.03 不随档位变
)
```

**官转三款**（`size` 参数严格控制 + `quality` 档位，示例用 `gpt-image-2.5-flare`，换 `sunburst` / `gpt-image-2` 只改模型名）：

```python theme={null}
client.images.generate(
    model="gpt-image-2.5-flare",
    prompt="...",
    size="2048x1152",   # ✅ 精确按此输出
    quality="max"       # 六档全开；xhigh / max 仅 2.5 两款；2.5 的 high 只等于 gpt-image-2 的 medium
)
```

### 3. 上传 / 输出格式差异

| 操作 | 官逆五款（`-all` / `2.5-all` / `-vip` 三款） | 官转三款（`2.5-flare` / `2.5-sunburst` / `gpt-image-2`） |
| - | - | - |
| **上传参考图** | multipart `image` 文件字段（edits 端点） | multipart `image[]` 文件字段 |
| **下载生成图** | 默认 `b64_json`（纯 base64，2026-07 实测），显式传 `response_format: "url"` 得 R2 CDN 链接（**24 小时有效期**） | `b64_json`（**纯 base64**，需 decode） |
| **多图融合** | edits 端点 `image` 字段重复传入多张 | `image[]` 数组重复传入，**最多 16 张** |

### 4. 价格示例（粗算）

| 场景 | 官逆五款（`-all` / `2.5-all` \$0.025；`-vip` 三款 \$0.03） | 官转 `gpt-image-2.5-flare` / `sunburst` | 官转 `gpt-image-2` |
| - | - | - | - |
| 1024×1024 草图（`low`） | \$0.025 / \$0.03 | \~\$0.006（196 tok） | \~\$0.006（196 tok） |
| 1024×1024 中等画质（`medium`） | \$0.025 / \$0.03 | \~\$0.013（439 tok） | \~\$0.053（1,756 tok） |
| 1024×1024 高画质（`high`） | \$0.025 / \$0.03 | \~\$0.053（1,756 tok） | \~\$0.211（7,024 tok） |
| 1024×1024 `xhigh` / `max` | \$0.03（`-vip` 2.5 两款可传，`gpt-image-2-vip` 只到 `high`，`-all` 不接受 `quality`） | \~\$0.094（3,122 tok）/ \~\$0.211（7,024 tok） | ❌ 不支持 |
| 2048×1152 高画质 | \$0.03（`-vip`；`-all` 只出 1K–1.5K） | 按 token 实计，`high` 约 `gpt-image-2` `medium` 的量级 | \~\$0.20+（按 token 实计） |
| 3840×2160 4K 高画质 | \$0.03（`-vip` 4K Detail 档，不加价；`-all` / `2.5-all` 不支持 4K） | 按 token 实计，**显著高于 1K** | 按 token 实计，**显著高于 1K** |
| 编辑 / 多图融合 | \$0.025 / \$0.03 | 输入 token 显著上升，单次成本可达 \$0.1+ | 输入 token 显著上升，单次成本可达 \$0.1+ |

以上官转数值按 2026-09-09 实测输出 token × \$30 / 百万粗算，未含提示词输入 token（通常不到 \$0.001）。

<Note>模型价格与官网对齐，且可能随官网调整；上表仅供参考，具体以顶部导航「模型价格」栏目为准：[模型价格](/models/index)。</Note>

<Info>
  **结论**：批量、低画质场景用官逆不一定省（草图 1K `low` 在官转上反而更便宜，2.5 两款连 `medium` / `high` 都只要 \~\$0.013 / \~\$0.053）；**中-高画质区段**（`gpt-image-2` 的 `medium` 以上、2.5 两款的 `xhigh` 以上）才是官逆 \$0.025–0.03 的甜点区。**需要 `quality` 档位 / mask 局部重绘 / 锁尺寸、4K / OpenAI 官方完全对齐字段** 时选官转（按量计费）。
</Info>

## 客户端调用建议

| 设置项 | 官逆五款（`-all` / `2.5-all` / `-vip` 三款） | 官转三款（`2.5-flare` / `2.5-sunburst` / `gpt-image-2`） |
| - | - | - |
| **超时（保守值）** | `-all` / `2.5-all`：**300 秒**（典型 \~90s）<br />`-vip` 三款：**300 秒**（典型 120–200s） | 2.5 两款 `low` / `medium`：**240 秒**；`high` / `xhigh`：**300 秒**；`max` 与 `gpt-image-2` `high`：**600 秒** 兜底（4K 高画质实测可达 3-5 分钟），分档表见 [官转概览](/api-capabilities/gpt-image-2/overview) |
| **重试策略** | 5xx 与超时指数退避 2 次 | 同左 |
| **并发** | 单次仅返回 1 张，多张请并发 | 单次 1 张，需要多张请并发 |
| **请求 ID** | `request-id` 响应头 | `x-request-id` 响应头 |

<Tip>
  **八个模型通用：图生图 / 多图融合时，单张输入图先压到 1.5MB 以内**（JPEG 质量 80-90 / 分辨率适当下调）。偶发的 `shell_api_error` / `Unknown error` 大多就是图片体积过大触发的，压一下请求成功率和出图速度都会明显改善。**输出分辨率与输入图体积无关**——画质看输出端配置（官转看 `size` + `quality`；`-vip` 三款看 `size` 档位 + `quality`；`-all` / `2.5-all` 看 prompt 画幅描述），不看输入端体积。
</Tip>

## 常见问题

<AccordionGroup>
  <Accordion title="输入图要压缩吗？提示词里写 4K / 8K 有用吗？">
    **强烈建议压**。八个模型上传给接口的图都先压到 **1.5MB 以内**（JPEG 质量 80-90 / 分辨率适当下调）：偶发的 `shell_api_error` / `Unknown error` 大多就是图片体积过大触发的，压一下请求成功率和出图速度都会明显改善。

    **别担心压输入会损画质**——输出分辨率与输入图体积无关，三类模型的"输出端"控制方式不同：

    * `gpt-image-2-all` / `gpt-image-2.5-all`：用 prompt 的画幅描述控制（参见 [-all 概览页的「经过验证的『提示词 → 实际分辨率』对照表」](/api-capabilities/gpt-image-2-all/overview)），prompt 光写 `4K` / `8K` 不算数
    * `-vip` 三款（`gpt-image-2.5-vip` / `gpt-image-2.5-flare-vip` / `gpt-image-2-vip`）：用 `size` 字段控制（30 档常见尺寸，含 4K），可再叠 `quality`（2.5 两款六档、`gpt-image-2-vip` 到 `high`）；prompt 光写 `4K` / `8K` 同样不算数
    * 官转三款（`gpt-image-2.5-flare` / `sunburst` / `gpt-image-2`）：用 `size` + `quality` 字段控制（任意合法尺寸）

    总结：压输入只会提速，不会损画质——画质看输出端配置，不看输入端体积。
  </Accordion>

  <Accordion title="同一个 API Key 八个模型都能用吗？">
    可以。八款都走默认分组（Default），同一个 API Key 同时调用即可，无需额外配置。注意：调用官转三款需要「按量优先」类型的令牌；`-all` / `-vip` 两种令牌类型都能用。
  </Accordion>

  <Accordion title="官逆推荐用哪些端点？">
    **统一使用 OpenAI Images API**（`/v1/images/generations` 文生图 + `/v1/images/edits` 图片编辑），理由有二：

    1. **更稳定**：上游对 Images API 通道的资源供给更充足，调用成功率更高
    2. **兼容官转，便于切换**：与官转三款（`gpt-image-2.5-flare` / `sunburst` / `gpt-image-2`）调用方式、参数格式完全兼容——官逆通道遇到风控异常时，**只需更换 `model` 名即可切到官转**，业务代码零改动

    另有对话式端点（`/v1/chat/completions`，**不主推**），仅适合多轮迭代改图、直接传在线图片 URL 的场景；注意出图意图不够明确时可能返回纯文字而不是图片（可在提示词开头加「生成图片：」前缀强化）。详细参数见 [-all 对话式调用说明](/api-capabilities/gpt-image-2-all/chat-completions) / [-vip 对话式调用说明](/api-capabilities/gpt-image-2-vip/chat-completions)。
  </Accordion>

  <Accordion title="官逆里 -all 和 -vip 怎么挑？">
    两条线都是逆向通道、**调用方式一致**（`-all` \$0.025/张、`-vip` \$0.03/张）（`-vip` 三款额外支持 `size` 锁尺寸与 `quality`，2.5 两款六档全开），差异是**速度 vs 画质 + 是否锁尺寸**：

    * **出图速度**：`-all` / `2.5-all` 约 90 秒——**快就是优势**；`-vip` 三款约 120–200 秒。当前比刚上线时慢，源自 OpenAI 官方算力波动
    * **画质**：`-vip`（Adobe 线）细节表现**有时更高**，适合不赶时间的精品图；三款里 sunburst-vip 与 `gpt-image-2-vip` 接近，flare-vip 偏软
    * **锁尺寸**：`-vip` 三款支持 30 档 `size`（含 4K）；`-all` / `2.5-all` 不接受 `size`，画幅写进 prompt

    决策：追求出图速度 → `-all` / `2.5-all`；要锁尺寸 / 4K → `-vip` 三款；要 30 档以外的自定义尺寸或精确 mask → 官转。详见 [GPT-Image-2.5-VIP 概览](/api-capabilities/gpt-image-2-vip/overview)。
  </Accordion>

  <Accordion title="-vip 里 2.5 两款和 gpt-image-2-vip 怎么挑？">
    三款同价、同分组、同调用（2026-09-09 同渠道同令牌 253 次三臂对比，契约逐格一致），差异只有三处：

    * **档位**：2.5 两款六档全开（`xhigh` / `max` 2026-09-10 放开），`gpt-image-2-vip` 到 `high`；同名档位不等价——2048×1152 下 2.5 的 `high` 1,413 = `gpt-image-2-vip` 的 `medium`，2.5 的 `max` 5,650 = `gpt-image-2-vip` 的 `high`。三款能到的最高 token 档相同
    * **画质与速度**：flare-vip 最快、画面偏软、装饰细节少；sunburst-vip 人眼与 `gpt-image-2-vip` 接近
    * **缺省尺寸**：flare-vip 固定 1024×1536，另外两款 2048×2048；要锁尺寸一律显式传 `size`

    别名 `gpt-image-2.5-vip` 就是 sunburst-vip。逐项对照表见 [GPT-Image-2.5-VIP 概览「三款 -vip 对比」](/api-capabilities/gpt-image-2-vip/overview)。
  </Accordion>

  <Accordion title="要锁尺寸 / 4K，现在怎么办？">
    首选 `-vip` 三款（默认 `gpt-image-2.5-vip`，要最高 token 档位选 `gpt-image-2-vip` + `high`）：`size` 参数已于 2026-07-22 恢复，支持 **30 档常见尺寸（10 比例 × 1K/2K/4K）**，统一 \$0.03/张、4K 不加价。注意 `size` 仅在 images 端点生效，且须为 30 档之一。

    需要 **30 档以外的任意合法尺寸**、**官方承诺的 `quality` 六档**（`-vip` 的档位属渠道行为不承诺）、**精确的 mask 局部重绘**（alpha 通道蒙版）或 **OpenAI 官方完全对齐字段**（已有官方 SDK 代码零改动迁移）时，走官转（`gpt-image-2.5-flare` / `sunburst` / `gpt-image-2`，按量计费、按 token 实计）。
  </Accordion>

  <Accordion title="想从 1.5 迁移，应该选哪个？">
    * **沿用官方 SDK / 要求与 OpenAI 官方一致，或要 30 档以外的任意自定义尺寸**：选官转（文生图 `gpt-image-2.5-flare`、改图 `gpt-image-2.5-sunburst`），需要删掉 `input_fidelity`，其它字段不动（`background: transparent` 照常可用）
    * **想顺便降低成本，追求出图速度**：选 `gpt-image-2.5-all`（官逆，\~90s；与 `gpt-image-2-all` 同价同行为）
    * **想顺便降低成本，画质优先或要锁尺寸 / 4K**：选 `gpt-image-2.5-vip`（官逆，\~120–200s，30 档 `size` 含 4K，`quality` 六档全开；`gpt-image-2-vip` 只到 `high`）
  </Accordion>

  <Accordion title="可以同时部署多个做兜底吗？">
    可以。常见做法：**主用 `2.5-all` 或 `2.5-vip`**（成本可预测，按速度 / 画质偏好选），**兜底用官转 `gpt-image-2.5-flare` / `sunburst`**（需要 `quality` 档位 / mask / 30 档以外自定义尺寸时切过去）。官转和官逆两类模型响应字段不同，业务层做一次格式归一即可。
  </Accordion>

  <Accordion title="图片下载链接（R2 CDN）很慢怎么办？">
    详见 [下载 CDN 图片/视频很慢怎么办？](/faq/cdn-download-slow)
  </Accordion>
</AccordionGroup>

## 相关文档

* [GPT-Image-2.5 / 2 概览](/api-capabilities/gpt-image-2/overview) - 官转三款完整接入文档
* [GPT-Image-2.5-All 概览](/api-capabilities/gpt-image-2-all/overview) - 官逆 ChatGPT 网页线（出图最快，`gpt-image-2.5-all` / `gpt-image-2-all`）完整接入文档
* [GPT-Image-2.5-VIP 概览](/api-capabilities/gpt-image-2-vip/overview) - 官逆 Adobe 线（`gpt-image-2.5-vip` 系列 + `gpt-image-2-vip`，支持 `size` 锁尺寸、`quality` 档位）完整接入文档
* [深度解读：GPT-image-2.5 上线](/news/gpt-image-2-5-launch) - 2.5 双模型上线说明
* [深度解读：gpt-image-2 上线](/news/gpt-image-2-launch) - 官转上线说明
* [深度解读：gpt-image-2-all 上线](/news/gpt-image-2-all-launch) - 官逆上线说明
* [社区贡献：Luck GPT-Image 2 ComfyUI 节点](/scenarios/ecosystem/luckgpt2-comfyui) - 多模型合一的 ComfyUI 节点包
* [社区贡献：APIYI GPT-Image 2 Skills](/scenarios/ecosystem/apiyi-gpt-image-skills) - 多模型合一的 AI Agent Skill 包
* [充值优惠活动](/faq/recharge-promotions) - 充值加赠政策


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.