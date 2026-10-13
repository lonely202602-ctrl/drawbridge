> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 图片 API 调用须知与最佳实践

> API易 图片 API 全部为同步调用：没有异步任务 ID，断开连接结果丢失但仍会计费。提供各模型 timeout 推荐值、base64 与 URL 输出对照表。

<Info>
  **一句话结论**：API易 所有图片模型均为**同步调用**——发出请求后保持连接等待，生成结果直接在响应里返回。没有异步任务 ID、没有轮询接口；客户端提前断开，这次的结果就拿不回来了，但请求仍会计费。因此，**留足 timeout 是图片 API 开发的第一原则**。
</Info>

## 三个必须先知道的事实

<CardGroup cols={3}>
  <Card title="全部同步调用" icon="arrow-right-left">
    一次 HTTP 请求全程阻塞等待，与官方接口形态一致，没有「提交任务 → 轮询结果」模式。部分上游本身是异步的（如 FLUX），也已被网关封装成同步，无需自己写轮询。
  </Card>

  <Card title="没有任务 ID" icon="search-x">
    不存在 task\_id 查询接口，也无法凭 request\_id 事后找回图片。API易 原厂透传、不存储生成结果，断开连接后结果不可恢复。
  </Card>

  <Card title="断连仍计费" icon="unplug">
    客户端超时主动断开后，服务端与上游的生成仍会跑完，该次请求**照常计费**。timeout 设得太小 = 花了钱却拿不到图。
  </Card>
</CardGroup>

## 模型系列速查表

各图片模型系列的推荐 timeout、输出格式与 URL 支持一览：

| 模型系列 | 端点 | 推荐 timeout | 输出格式 | URL 输出与有效期 |
| - | - | - | - | - |
| [GPT-Image-2.5 / 2（官转，flare / sunburst / 2）](/api-capabilities/gpt-image-2/overview) | `/v1/images/generations`、`/v1/images/edits` | **360 秒**（gpt-image-2 high + 2K/4K 实测 3-5 分钟；2.5 更快但 `xhigh` / `max` 同样建议留足） | 纯 b64\_json（**无 `data:` 前缀**） | ❌ 不支持（传 `response_format` 直接 400；`image2_OSS` 分组暂不支持官转） |
| [GPT-Image-2-All（官逆）](/api-capabilities/gpt-image-2-all/overview) | 同上 | **300 秒** | 默认 `b64_json`（**无 `data:` 前缀**，2026-07 实测）；显式 `response_format: "url"` 可切换 | ✅ 显式 `url`：R2 CDN 约 24 小时；强依赖 URL 用 `image2_OSS` 分组 |
| [GPT-Image-2-VIP](/api-capabilities/gpt-image-2-vip/overview) | 同上 | **300 秒** | 同 All（默认 `b64_json` 无前缀，2026-07 实测） | ✅ 同 All（显式 `url` 或 `image2_OSS` 分组） |
| [Nano Banana Pro](/api-capabilities/nano-banana-image/overview) | Gemini 原生 `:generateContent` | 1K/2K **300 秒**、4K **600 秒**、多图参考 5 分钟以上 | `inlineData.data` 纯 base64 | `NB_OSS` 内测分组（见下文） |
| [Nano Banana 2](/api-capabilities/nano-banana-2-image/overview) | 同上 | **360 秒** | 同上 | `NB_OSS` 覆盖范围请咨询客服 |
| [Nano Banana Lite](/api-capabilities/nano-banana-lite-image/overview) | 同上 | **300 秒**（常规约 4 秒出图，为高峰拥塞留余量） | 同上 | `NB_OSS` 覆盖范围请咨询客服 |
| [FLUX](/api-capabilities/flux/overview) | `/v1/images/generations` | **60-120 秒**，flex 系列建议 180 秒 | 仅 `data[0].url`（**原厂默认即 URL**） | ⚠️ 仅约 **10 分钟**有效、无 CORS，必须服务端立即转存 |
| [Seedream](/api-capabilities/seedream-image/overview) | `/v1/images/generations`（生成/编辑统一端点） | **60 秒**（4K + hd 约 30-60 秒） | 默认 `url`（**原厂默认即 URL**）；可选 `b64_json`（纯 base64 无前缀） | ✅ BytePlus TOS，约 24 小时 |
| [MAI-Image 2.6](/api-capabilities/mai-image/overview) | `/v1/images/generations`、`/v1/images/edits` | Flash **120 秒**、2.6 **180 秒**（1024×1024 约 17 / 30 秒） | 纯 b64\_json（**无 `data:` 前缀**），PNG | ❌ 不支持（传 `response_format` 直接 400） |

<Tip>
  `response_format` 参数的**适用面很窄**：仅 GPT-Image-2-All / VIP 和 Seedream 支持；官转 GPT-Image-2 传了会直接返回 400 `unknown_parameter`，MAI-Image 2.6 传了同样返回 400。支持该参数的模型建议**始终显式传值**，不要依赖默认行为——历史上默认值随分组和负载变化过。
</Tip>

## 计费与价格影响

新手最常问的计费问题：「参考图是每张定量，还是图片越大消耗 token 越多？」先建立三个直觉：

<CardGroup cols={3}>
  <Card title="成本大头是输出" icon="trending-up">
    以 gpt-image-2 为例：文本输入 \$5/M、图片输入 \$8/M、**输出 \$30/M**。影响价格最大的永远是**输出的尺寸和画质**（quality × size），其次才是参考图张数。
  </Card>

  <Card title="输入图不是每张定量" icon="scaling">
    GPT 系输入图按**尺寸/宽高比映射**成 tokens（越大越多，但有下限也有封顶），**张数严格线性累加**。Gemini 系则相反——输出图按分辨率档固定 token/张。
  </Card>

  <Card title="以接口返回的 usage 为准" icon="receipt">
    输入/输出 token 都在响应里：GPT 系看 `usage.input_tokens_details.image_tokens`，Gemini 系看 `usageMetadata.promptTokensDetails`。对账、核价都以此为准，不要按张数估。
  </Card>
</CardGroup>

### 两大体系的 token 口径对照

| 体系 | 输入图 tokens | 输出图 tokens |
| - | - | - |
| **GPT 系**（gpt-image-2 等） | 按尺寸动态换算：≤1024² 方图统一 1024，2048² 以上封顶 1521（2026-07 实测）；**N 张 = N × 单张** | 由 `size` × `quality` 决定：1024² 从 low 196 到 high 数千 tokens |
| **Gemini 系**（Nano Banana 全系） | 计入 `promptTokensDetails` 的 IMAGE 模态 | **按分辨率档固定**：1K/2K 每张 1120、4K 每张 2000，与宽高比无关 |

### 多图输入的费用直觉

* 单张参考图约 **800-1600 image tokens ≈ \$0.008-0.012**（gpt-image-2 实测，含尺寸/宽高比浮动）；
* 张数线性累加：**16 张 ≈ \$0.13**，与一张 `high` 输出（≈\$0.21）同量级——多图融合场景输入成本不可忽略；
* **token 由像素尺寸决定、与文件体积无关**：压缩体积是为上传稳定，不省 token；省 token 靠减少张数（超大图有封顶，不必担心费用爆炸）。

完整实测数据表见 [gpt-image-2 多图输入的价格影响](/api-capabilities/gpt-image-2/overview#多图输入的价格影响2026-07-实测)；Gemini 系 token 口径详见 [usageMetadata 解读](/api-capabilities/nano-banana-usage-metadata) 与 [Nano Banana 价格说明](/api-capabilities/nano-banana-pricing)。

## timeout 配置建议

### 为什么默认 timeout 会误伤

主流 HTTP 客户端的默认超时普遍在 30-60 秒（`requests` 甚至默认不限时但常被框架包一层 30 秒），而图片生成是真正的「长请求」：

* GPT-Image-2 在 `high` 画质 + 2K/4K 分辨率下，实测整体耗时 **3-5 分钟**；
* Nano Banana 系列 4K 出图约 50 秒起步，高峰期更久；
* 多图融合、图片编辑类请求普遍比文生图更慢。

按默认值配置，请求会在服务端还在正常生成时被客户端掐断——表现为大量「超时失败」，实际上是**自己断开了本来会成功的请求**，而且这些请求照常计费。

### 按模型分档设置

```python theme={null}
# 按模型设置客户端超时（秒），而不是全局一个值
IMAGE_TIMEOUTS = {
    "gpt-image-2": 360,                      # high + 2K/4K 实测 3-5 分钟
    "gpt-image-2.5-flare": 360,              # 2.5 同价同参数，超时经验沿用
    "gpt-image-2.5-sunburst": 360,
    "gpt-image-2-all": 300,
    "gpt-image-2-vip": 300,
    "gemini-3-pro-image": 600,               # Nano Banana Pro，4K 按 600s 兜底
    "gemini-3.1-flash-image-preview": 360,   # Nano Banana 2
    "gemini-3.1-flash-lite-image": 300,      # Nano Banana Lite
    "flux": 120,                             # flex 系列建议 180
    "seedream": 60,                          # 4K + hd 约 30-60 秒
    "MAI-Image-2.6-Flash": 120,              # 1024×1024 约 17 秒
    "MAI-Image-2.6": 180,                    # 1024×1024 约 30 秒
}

import requests

def generate_image(model: str, payload: dict, api_key: str) -> dict:
    resp = requests.post(
        "https://api.apiyi.com/v1/images/generations",
        headers={"Authorization": f"Bearer {api_key}"},
        json={"model": model, **payload},
        timeout=IMAGE_TIMEOUTS.get(model, 300),  # 未知模型给 300s 兜底
    )
    resp.raise_for_status()
    return resp.json()
```

### 重试策略

不是所有失败都值得重试，先分清计费口径：

| 失败情况 | 是否计费 | 建议做法 |
| - | - | - |
| 429 / 503（限流、上游过载） | 不计费 | 指数退避后重试（如 5s、15s、45s） |
| 客户端超时主动断开 | **仍计费** | 优先加大 timeout；确需重试时谨慎控制次数 |
| 400 / 403（参数、权限错误） | 不计费 | 修正请求再发，盲目重试无意义 |
| 内容审核拦截 | **分情况**（见下方说明） | 修改 prompt 后再试，原样重发大概率再次被拦 |

<Info>
  **内容审核拦截的计费分情况**：按 token 计费的模型（gpt-image-2 官转等）触发审核通常直接返回 400 错误，**不计费**；**仅按次计费的 Nano Banana Pro** 会遇到「HTTP 200 但出图失败」的谷歌侧拦截，该次**会计费**——API易 对此类非主观失败提供 [出图失败包补计划](/api-capabilities/nano-banana-pro-guarantee)，按条数核算后补发额度。
</Info>

## base64 数据处理要点

### 前缀差异对照

不同系列返回的 base64 字段格式**并不统一**，这是新接入时最常见的坑：

| 模型系列 | base64 所在字段 | 是否含 `data:image/...;base64,` 前缀 |
| - | - | - |
| GPT-Image-2（官转） | `data[0].b64_json` | 无前缀（纯 base64） |
| GPT-Image-2-All / VIP | `data[0].b64_json` | 无前缀（2026-07 实测；**历史版本曾含前缀**） |
| Nano Banana 系列 | `candidates[0].content.parts[].inlineData.data` | 无前缀（纯 base64） |
| Seedream（`b64_json` 模式） | `data[0].b64_json` | 无前缀（纯 base64） |

前缀行为随渠道版本变化过，写代码时**务必先做 `startsWith("data:")` 检测**：有前缀的剥掉前缀再解码（或直接用作 `img src`），无前缀的直接解码，避免「双重拼接」或「带前缀解码」产出损坏的图片。

### 解码写文件

```python theme={null}
import base64

b64 = response["data"][0]["b64_json"]
if b64.startswith("data:"):          # 防御性剥前缀，兼容曾出现过的 data: 前缀响应
    b64 = b64.split(",", 1)[1]
with open("output.png", "wb") as f:
    f.write(base64.b64decode(b64))
```

```javascript theme={null}
let b64 = response.data[0].b64_json;
if (b64.startsWith("data:")) {
  b64 = b64.slice(b64.indexOf(",") + 1);
}
require("fs").writeFileSync("output.png", Buffer.from(b64, "base64"));
```

### Playground 渲染限制

base64 模式的响应往往有数 MB，浏览器 Playground 可能弹出 `请求时发生错误: unable to complete request`——这**不代表请求失败**，实际请求已成功并已计费，只是浏览器无法渲染这么长的字符串。验证效果请用代码调用，或改用支持 `url` 输出的模型/参数。

## 输入图片格式预处理

图片编辑 / 参考图类接口（如 gpt-image-2 的 `/v1/images/edits`）对输入图片只接受 **png / jpg / webp** 三种标准格式。「用户上传实拍图」类业务最容易踩一个隐蔽的坑：**手机原拍照片经常不是标准 JPEG**。

### 典型症状：400 invalid\_image\_file

```json theme={null}
{
  "error": {
    "message": "Invalid image file or mode for image 1, please check your image file. ...",
    "code": "invalid_image_file"
  }
}
```

根因通常是 **MPO 格式**（Multi-Picture Object，多帧 JPEG 容器）：华为 Mate 系列等机型直出的 `.jpg` 内嵌 HDR 增益图副帧，实为 MPO。这类文件的隐蔽性在于——文件头同为 `FFD8`，**扩展名、HTTP Content-Type、`file` 命令全都显示 JPEG**，只有按帧解析才能识别：

```python theme={null}
from PIL import Image
Image.open("photo.jpg").format   # 返回 "MPO" 即中招；标准图返回 "JPEG"/"PNG"
```

2026-07 实测（gpt-image-2 编辑接口）：MPO 图必被拒；同一张图重编码为标准 JPEG/PNG 后**保持原分辨率（3072×4096）上传即成功**——问题在格式，不在尺寸。此类 400 在入口校验阶段快速返回，**不计费**。

### 建议：服务端统一重编码

与其逐张排查，不如在上传链路统一做一次重编码，顺带兼容 HEIC、CMYK 等其它非标准输入：

```python theme={null}
from PIL import Image
import io

def normalize_image(raw: bytes) -> bytes:
    """任意来源图片 → 标准 JPEG，通过各图片编辑接口的格式校验"""
    im = Image.open(io.BytesIO(raw))
    im.load()                      # 多帧格式（MPO 等）只取第一帧
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGB")     # CMYK / P 等模式统一转 RGB
    out = io.BytesIO()
    im.save(out, format="JPEG", quality=92)
    return out.getvalue()
```

重编码时顺手把体积也压下来（长边 ≤ 4096、JPEG 质量 80-92），单张控制在 1.5MB 以内，上传成功率和出图速度都会更好——输出画质与输入图体积无关。详见 [gpt-image-2 图片编辑「参考图格式要求与预处理」](/api-capabilities/gpt-image-2/image-edit#参考图格式要求与预处理)。

## 需要 URL 输出怎么办

一共三条路径，按可靠程度排序：

1. **原厂默认就是 URL**：FLUX（仅约 10 分钟有效且无 CORS 头，必须服务端立即下载转存）和 Seedream（BytePlus TOS，约 24 小时）的原厂输出格式本身就是 URL，无需任何配置。
2. **OSS 分组（确定性 URL 输出，推荐生产使用）**：
   * `image2_OSS` 分组：适用于 **GPT-Image-2-All / VIP**（1x 倍率、不加价），令牌分组切换后稳定输出 URL、不降级为 base64；**官转 GPT-Image-2 暂不支持**。
   * `NB_OSS` 内测分组：适用于 Nano Banana 系列，图片 URL 出现在 `text` 字段中，详见 [NB-OSS 分组说明](/api-capabilities/nano-banana-oss-group)。
3. **显式传 `response_format: "url"`**：仅 GPT-Image-2-All / VIP（R2 CDN，约 24 小时）和 Seedream 支持，**适用面窄**——官转 GPT-Image-2 传了直接 400。默认分组下这是逐请求切换，强依赖 URL 的业务建议直接用 OSS 分组。

**GPT-Image-2（官转）目前没有任何 URL 输出途径**，仅 base64。

<Warning>
  所有平台返回的图片 URL 都是**临时链接**（10 分钟到 24 小时不等），过期后 404。任何需要长期保存的场景（商品图、用户作品、历史记录），都必须在拿到结果后**立即转存到自己的对象存储 / CDN**，再把自己的 URL 落库。
</Warning>

## 超时与断连排查

如果你已经把 SDK timeout 调大了却仍然频繁「超时」，按这个顺序排查：

<Steps>
  <Step title="确认客户端 SDK 的真实 timeout">
    有些框架会在 HTTP 客户端外再包一层超时（如任务队列的 worker 超时、Serverless 函数的执行上限），任何一层小于模型生成时间都会掐断请求。
  </Step>

  <Step title="排查中间层：nginx / 负载均衡 / CDN">
    自建反向代理的 `proxy_read_timeout`、云负载均衡的空闲连接超时、CDN 的回源超时**默认值普遍是 60 秒**，会先于你的客户端断开连接。长请求链路上的每一跳都要放宽。
  </Step>

  <Step title="启用 keep-alive，避免连接被中间设备回收">
    长时间无字节传输的连接可能被 NAT / 防火墙静默回收，开启 TCP keep-alive 或 HTTP keep-alive 可显著降低概率。
  </Step>

  <Step title="用请求 ID 与后台日志确认是否已计费">
    记录响应头中的 `x-request-id`，再到 API易 后台的调用日志中核对：如果日志里能查到这次调用，说明服务端已完成生成并计费，问题出在你这一侧的连接被提前断开。
  </Step>
</Steps>

## 想做异步任务管理？

平台不提供异步接口，但你完全可以在同步接口之上自建异步外壳：

<CardGroup cols={3}>
  <Card title="为什么没有异步接口" icon="circle-question-mark" href="/faq/image-async-api">
    FAQ：图片生成有异步接口吗？支持任务 ID 查询结果吗？
  </Card>

  <Card title="自实现异步队列" icon="list-checks" href="/api-capabilities/image-async-queue">
    工程实践：把同步调用包进任务队列，自己生成 task\_id、落库、重试
  </Card>

  <Card title="NB-OSS URL 输出分组" icon="cloud-upload" href="/api-capabilities/nano-banana-oss-group">
    Nano Banana 系列改为 URL 输出，减轻 base64 传输压力
  </Card>
</CardGroup>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.