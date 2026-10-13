> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 蒙版局部重绘指南

> gpt-image-2 蒙版（mask）局部重绘完整指南 — Alpha 通道原理、蒙版制作与校验、Python/cURL/Node.js 示例、常见报错排查

<Info>
  本页是 `gpt-image-2` 通过 `POST /v1/images/edits` 上传**原图 + 蒙版（mask）+ 提示词**实现局部重绘的实操指南。接口参数与在线调试请见 [图片编辑 API 参考](/api-capabilities/gpt-image-2/image-edit)。
</Info>

## 核心原理：Alpha 通道决定编辑区域

一次局部编辑请求由三部分组成：

```text theme={null}
原始图片 image
+ 蒙版图片 mask
+ 编辑指令 prompt
= 编辑后的完整图片
```

蒙版通过 PNG 的 **Alpha 透明通道**标记修改区域：

| 蒙版区域 | Alpha 值 | 作用 |
| - | -: | - |
| 完全透明 | 0 | 允许模型编辑 |
| 完全不透明 | 255 | 尽量保留原图 |
| 半透明 | 1～254 | 过渡区域，不建议作为精确规则依赖 |

<Warning>
  **最容易搞反的一点**：决定编辑区域的是 **Alpha 通道**，不是肉眼看到的黑色或白色。

  ```text theme={null}
  透明区域   = 要修改的区域
  不透明区域 = 尽量不要修改的区域
  ```

  一张"看起来黑白分明"的 PNG，如果没有 Alpha 通道，直接上传会报 `invalid_image_file`。
</Warning>

### 一个直观例子

假设原图尺寸是 1024×1024：

```text theme={null}
┌──────────────────────┐
│      不透明区域       │
│   尽量保持原图不变     │
│                      │
│       ┌──────┐       │
│       │透明区│       │
│       │换成花│       │
│       └──────┘       │
└──────────────────────┘
```

配合提示词：

```text theme={null}
把透明区域中的水杯替换成一束白色郁金香，
其他区域保持不变，保持原有光线、视角和摄影风格。
```

## Mask 不是硬裁剪

GPT Image 的蒙版**不是** Photoshop 那种绝对像素级的硬限制。官方说明其本质仍是**基于提示词的引导式编辑**：模型会参考蒙版，但不保证严格按每一个像素边界执行。

因此可能出现：

* 蒙版外的阴影略微变化
* 物体边缘向外扩展
* 光照和反射发生联动
* 背景细节被轻微重绘
* 蒙版边界附近出现过渡

这对自然融合是好事，但不适合要求绝对像素不变的场景（解决方案见下文「严格保持蒙版外不变」）。

### 提高局部编辑稳定性

提示词不要只写「换成红色衣服」，建议写成：

```text theme={null}
仅修改蒙版透明区域，将人物上衣替换成纯红色圆领棉质短袖。
保持人物脸部、头发、身体姿势、手臂、背景、构图、相机视角、
光照方向和图片尺寸不变。新衣服需要自然贴合人物身体，并保持
原图的真实摄影质感。
```

实务建议：

1. 蒙版比目标物体边缘稍微扩大一些
2. 不要只遮住物体中心，要覆盖物体边缘、阴影和反射
3. 明确写出哪些内容必须保持不变
4. 编辑区域过小时，适当扩大蒙版
5. 要求绝对不变时，最后自行做一次像素合成（见下文）

## 要不要用 Mask？与纯提示词编辑的取舍

一个常见疑问：**现在的 AI 不是已经"指哪打哪"了吗，为什么还要费劲做蒙版？**

确实，`gpt-image-2` 不传 mask、只靠一句"把左边桌上的杯子换成花"，多数时候就能改对地方——指令遵循能力已经很强，日常随手改图完全够用。但 mask 解决的是**提示词说不清、或者说清了也不保险**的问题：

| 场景 | 纯提示词 | 提示词 + Mask |
| - | - | - |
| 画面只有一个目标物 | ✅ 够用，mask 属于多余 | 不必要 |
| 多个相似物体只改其一（三个人只换中间那位的衣服） | ⚠️ 容易误伤同类目标 | ✅ 空间指定，零歧义 |
| 边界要求严格（商品图 / UI 截图 / 证件版式） | ❌ 整图重生成，蒙版外也会漂移 | ✅ 配合像素合成可做到严格不变 |
| 批量流水线（同一版式反复替换某区域） | ⚠️ 每次结果不稳定 | ✅ 蒙版可程序化生成，结果可复现 |
| 精确形状 / 位置控制（把物体挪到指定坐标） | ❌ 语言难以描述像素级位置 | ✅ 蒙版就是像素级坐标 |

学术研究也支持这个分工：mask-free（纯文字驱动）的编辑方法难以精确控制空间位置和形状——比如 prompt-to-prompt 类方法无法在画面中**空间移动**一个物体，编辑区域覆盖不准时还会"该改的没改、不该改的改了"；而 mask-based 方法以牺牲一点便利为代价，换来明确的空间控制精度。

<Tip>
  **一句话结论**：mask 不是被淘汰的旧技术，而是从"必需品"变成了"精度控制工具"。聊天式随手改图 → 直接用提示词；生产环境要求可复现、可控、边界严格 → 用 mask。另外别忘了：`gpt-image-2` 纯提示词编辑本质是**整图重生成**，未指定区域同样可能变化——这正是 mask + 像素合成存在的意义。
</Tip>

## 文件要求速查

| 项目 | 要求 |
| - | - |
| 原图格式 | PNG / JPG / WebP，单张小于 50MB |
| 输入图数量 | 最多 16 张（`image[]` 重复传入） |
| 蒙版格式 | **PNG，必须带 Alpha 通道** |
| 蒙版尺寸 | 与**第一张**原图宽高**完全一致**（差 1 像素也不行） |
| 蒙版大小 | 小于 **4MB** |
| 蒙版作用范围 | 仅作用于 `image[0]`（第一张图） |

多图编辑时的角色分配：

```text theme={null}
image[0]  = 主要编辑画布
image[1…] = 参考图
mask      = 只作用于 image[0]
```

<Tip>
  「尺寸完全一致」听起来麻烦，实际不需要手动对齐——蒙版都是**从原图上派生**出来的（在原图副本上擦除 / 涂抹 / 分割），同尺寸自动满足，详见下文 [蒙版从哪来](#蒙版从哪来五种常见制作方式)。
</Tip>

## Python 调用示例

```python theme={null}
import base64
from pathlib import Path
from openai import OpenAI

client = OpenAI(
    api_key="sk-your-api-key",
    base_url="https://api.apiyi.com/v1"
)

with open("input.png", "rb") as image_file, \
     open("mask.png", "rb") as mask_file:

    result = client.images.edit(
        model="gpt-image-2.5-sunburst",
        image=image_file,
        mask=mask_file,
        prompt=(
            "仅修改蒙版透明区域。"
            "把桌面上的白色水杯替换成一束白色郁金香，"
            "保持桌面、背景、相机视角、构图和光线不变。"
            "花束需要自然放置在原水杯的位置，并产生符合原图光线的阴影。"
        ),
        size="1536x1024",
        quality="high",
        output_format="png",
        n=1,
    )

image_bytes = base64.b64decode(result.data[0].b64_json)
Path("edited.png").write_bytes(image_bytes)
print("图片已保存：edited.png")
```

<Warning>
  **不要传 `input_fidelity="high"`** —— 三款模型对输入图默认始终高保真处理，API 不允许调整该参数，传了会 400 报错（2.5 实测 2026-09-09 同样 400），直接省略即可。
</Warning>

## cURL 调用示例

图片编辑接口必须使用 `multipart/form-data`，不能把原图和蒙版作为普通 JSON 字段提交：

```bash theme={null}
curl -s \
  -X POST "https://api.apiyi.com/v1/images/edits" \
  -H "Authorization: Bearer sk-your-api-key" \
  -F "model=gpt-image-2.5-sunburst" \
  -F "image[]=@input.png;type=image/png" \
  -F "mask=@mask.png;type=image/png" \
  -F "prompt=仅修改蒙版透明区域，把桌上的水杯替换成一束白色郁金香，其他区域保持不变，保持原有构图、光线和真实摄影风格。" \
  -F "size=1536x1024" \
  -F "quality=high" \
  -F "output_format=png" \
  | jq -r '.data[0].b64_json' \
  | base64 --decode > edited.png
```

即便只有一张图片，也建议按官方示例使用 `image[]` 字段名。

<Warning>
  使用 `-F` 时**不要手动设置** `-H "Content-Type: multipart/form-data"`。curl 需要自动生成 `boundary`，手动设置会丢失 boundary，导致服务器无法识别文件。
</Warning>

## Node.js 调用示例

```javascript theme={null}
import fs from "fs";
import OpenAI, { toFile } from "openai";

const client = new OpenAI({
  apiKey: "sk-your-api-key",
  baseURL: "https://api.apiyi.com/v1",
});

const image = await toFile(
  fs.createReadStream("input.png"),
  "input.png",
  { type: "image/png" }
);

const mask = await toFile(
  fs.createReadStream("mask.png"),
  "mask.png",
  { type: "image/png" }
);

const result = await client.images.edit({
  model: "gpt-image-2.5-sunburst",
  image,
  mask,
  prompt:
    "仅修改蒙版透明区域。把桌面上的白色水杯替换成一束白色郁金香，" +
    "保持背景、构图、镜头视角和光线不变。",
  size: "1536x1024",
  quality: "high",
  output_format: "png",
});

const imageBuffer = Buffer.from(result.data[0].b64_json, "base64");
fs.writeFileSync("edited.png", imageBuffer);
console.log("图片已保存：edited.png");
```

## 蒙版从哪来？五种常见制作方式

很多人觉得做蒙版麻烦——"还得跟原图一模一样的尺寸"。其实有一个关键认知：**蒙版几乎从不'另画一张'，而是从原图上派生出来的**。无论用代码、修图软件还是网页画布，流程都是「打开原图 → 在原图上标记区域 → 导出」，尺寸一致是**自动满足**的，不需要手动对齐。

| 方式 | 适合场景 | 上手成本 |
| - | - | - |
| ① 代码生成（PIL 画区域） | 固定版式批量处理、坐标已知 | 低（几行代码） |
| ② 修图软件手动擦除 | 一次性精修、形状复杂 | 低（会用选区即可） |
| ③ 网页涂抹画布 | 自建产品给用户"刷一下"就改 | 中（前端 Canvas） |
| ④ AI 自动分割（SAM 系） | 点一下 / 一句话就出精确蒙版 | 中（需部署或调用分割服务） |
| ⑤ 黑白蒙版转 Alpha | 承接其它工具输出的黑白 mask | 低（几行代码） |

### 方法一：程序直接生成透明蒙版

把矩形区域设为透明（允许编辑）：

```python theme={null}
from PIL import Image, ImageDraw

original = Image.open("input.png").convert("RGBA")

# 默认整张蒙版完全不透明：尽量保留
mask = Image.new("RGBA", original.size, (255, 255, 255, 255))

draw = ImageDraw.Draw(mask)

# 把指定区域设为完全透明：允许编辑
# 坐标格式：(左, 上, 右, 下)
draw.rectangle((300, 250, 750, 800), fill=(0, 0, 0, 0))

mask.save("mask.png")
print("蒙版尺寸：", mask.size)
```

* `(255, 255, 255, 255)` = 不透明，保留区
* `(0, 0, 0, 0)` = 透明，编辑区

### 方法二：修图软件手动擦除

任何支持透明 PNG 的修图软件（Photoshop、GIMP、Krita、Photopea 等）都能做蒙版，本质就一步：**把要编辑的区域"擦"成透明**。以 Photoshop 为例：

1. 打开**原图副本**（直接在原图上操作，尺寸天然一致）
2. 如果图层是「背景」，先双击解锁为普通图层（背景图层不支持透明）
3. 用套索 / 快速选择 / 对象选择工具框选要修改的区域
4. 按 Delete 删除选区内容 → 露出透明棋盘格
5. 「导出为 PNG」（勾选透明度），得到的就是合格的 Alpha 蒙版

GIMP 同理：`图层 → 透明 → 添加 Alpha 通道`，选区后 Delete，导出 PNG。

<Tip>
  形状完全不限于矩形——套索沿着物体轮廓走、快速选择一键选中主体，擦出来的透明区就是任意不规则形状。建议选区比物体轮廓**稍微外扩几个像素**（Photoshop：`选择 → 修改 → 扩展`），把阴影和边缘一并覆盖。
</Tip>

### 方法三：网页涂抹画布

各类 AI 修图产品里"用笔刷涂一下要改的地方"的交互，就是在浏览器里动态生成 Alpha 蒙版，核心只有一个 Canvas API 属性。实现原理见下文 [涂抹式修图的实现原理](#蒙版形状与涂抹式修图的实现原理)。

### 方法四：AI 自动分割一键出蒙版

手动涂抹也嫌麻烦？可以让分割模型代劳。Meta 开源的 **SAM（Segment Anything Model）** 系列是目前的主流方案：

* **点选出蒙版**：在物体上点一下，模型输出该物体的像素级精确轮廓（连头发丝边缘都能贴合）
* **文字出蒙版**：2025 年 11 月开源的 **SAM 3** 支持概念级文字提示，如「所有黄色出租车」「穿红色球衣的球员」，一次返回所有匹配实例的蒙版（模型与代码见 `github.com/facebookresearch`，介绍见 `ai.meta.com`）
* **抠主体 / 抠背景**：`rembg` 这类开源工具一行命令分离主体与背景，背景区域直接可以当"只改背景"的蒙版用

拿到分割结果（通常是黑白位图）后，用下面「方法五」转成 Alpha 蒙版即可。Stable Diffusion 社区的 **Inpaint Anything** 插件、ComfyUI 的 Mask Editor 就是「SAM 分割 + 笔刷微调 → 蒙版 → 局部重绘」这套流水线的成熟实现，思路可以直接借鉴。

### 方法五：把黑白蒙版转换成 Alpha 蒙版

如果你已有一张「黑色 = 编辑，白色 = 保留」的黑白蒙版：

```python theme={null}
from PIL import Image

bw_mask = Image.open("mask_bw.png").convert("L")

rgba_mask = Image.new("RGBA", bw_mask.size, (255, 255, 255, 255))

# 黑色值 0   → Alpha 0   → 透明   → 编辑
# 白色值 255 → Alpha 255 → 不透明 → 保留
rgba_mask.putalpha(bw_mask)

rgba_mask.save("mask.png")
```

### 上传前先校验蒙版

很多 `invalid_image_file` 都是因为文件扩展名是 `.png`，实际却只有 RGB 没有 Alpha。上传前跑一遍：

```python theme={null}
from PIL import Image

image = Image.open("input.png")
mask = Image.open("mask.png")

print("蒙版格式：", mask.format)
print("蒙版模式：", mask.mode)
print("蒙版尺寸：", mask.size)

assert mask.format == "PNG", "蒙版必须是 PNG"
assert mask.mode in ("RGBA", "LA"), "蒙版必须包含 Alpha 通道"
assert image.size == mask.size, "原图和蒙版尺寸必须完全一致"

alpha = mask.getchannel("A")
assert alpha.getextrema()[0] == 0, "蒙版中没有完全透明的编辑区域"

print("蒙版检查通过")
```

## 蒙版形状与涂抹式修图的实现原理

### 蒙版可以是任意不规则形状

蒙版本质是一张**逐像素的位图**，不是几何图形——每个像素独立记录一个 Alpha 值。所以：

* 矩形、圆形只是最简单的示例
* 沿人物轮廓的剪影、头发丝边缘、随手涂鸦的一团、不连通的多块区域，全部合法
* 实践中**大多数蒙版都是不规则的**：跟着目标物体的轮廓走，再略微外扩

<Tip>
  唯一的"形状建议"与规则无关，与效果有关：透明区要**完整覆盖物体本体 + 边缘 + 阴影 + 反射**，宁可多圈一点，让模型有空间做自然融合。
</Tip>

### 涂抹式修图是怎么实现的

各类修图 App 里"笔刷涂哪改哪"的交互，前端实现出奇地简单：**两层画布叠加，笔刷把上层"擦"成透明**。

```text theme={null}
底层 <img>       显示原图（仅供用户参考，不参与导出）
上层 <canvas>    与原图等宽高，初始整张填充为不透明
                 笔刷经过处 → 像素变透明
导出 canvas      → 就是一张合格的 Alpha 蒙版 PNG
```

核心只有一行——把 Canvas 合成模式设为 `destination-out`（新笔迹从已有像素中"挖掉"内容）：

```javascript theme={null}
const canvas = document.getElementById("mask-canvas");
const ctx = canvas.getContext("2d");

// 1. 画布尺寸 = 原图原始像素尺寸（不是 CSS 显示尺寸），同尺寸自动满足
canvas.width = image.naturalWidth;
canvas.height = image.naturalHeight;

// 2. 初始整张不透明 = 全部保留
ctx.fillStyle = "rgba(255, 255, 255, 1)";
ctx.fillRect(0, 0, canvas.width, canvas.height);

// 3. 关键一行：笔刷改为"擦除"模式，画过的地方变透明
ctx.globalCompositeOperation = "destination-out";
ctx.lineWidth = 40;          // 笔刷粗细
ctx.lineCap = "round";
ctx.strokeStyle = "rgba(0, 0, 0, 1)";

// 4. 监听鼠标 / 触摸事件画线（注意把显示坐标换算回原始像素坐标）
canvas.addEventListener("pointermove", (event) => {
  if (!drawing) return;
  const scaleX = canvas.width / canvas.clientWidth;
  const scaleY = canvas.height / canvas.clientHeight;
  ctx.lineTo(event.offsetX * scaleX, event.offsetY * scaleY);
  ctx.stroke();
});

// 5. 导出：直接得到带 Alpha 通道的 PNG 蒙版
canvas.toBlob((blob) => {
  const formData = new FormData();
  formData.append("mask", blob, "mask.png");
  // 连同原图、prompt 一起 POST /v1/images/edits
}, "image/png");
```

几个工程细节：

1. **坐标换算**：画布在页面上通常被 CSS 缩小显示，笔迹坐标要按 `原始宽 / 显示宽` 的比例换算回去，否则蒙版错位
2. **撤销**：每笔开始前 `ctx.getImageData()` 存快照，撤销时 `putImageData()` 恢复
3. **蒙版膨胀（dilate）**：用户涂抹往往只盖住物体中心，提交前程序性外扩几个像素（专业工具里的「Expand Mask」按钮就是这个），Python 端可用 `PIL.ImageFilter.MaxFilter` 或 OpenCV `cv2.dilate` 实现
4. **半透明预览**：给用户看的涂抹高亮（如红色半透明）画在**另一个预览层**上，导出的蒙版层保持纯粹的"不透明 / 透明"二值

### 进阶：点选 / 文字自动出蒙版

涂抹式再往前一步，就是把"人手涂"换成"模型算"：

```text theme={null}
用户点一下物体          → SAM 输出该物体的像素级轮廓蒙版
用户输入「左边的杯子」   → SAM 3 按文字概念找出所有匹配实例
程序自动 dilate + 羽化  → 提交 /v1/images/edits
```

这正是 Inpaint Anything、ComfyUI Mask Editor 等工具的做法：**分割模型负责"准"，笔刷负责"改"**——先一键生成精确蒙版，再用笔刷做加减微调（Add / Trim mask by sketch）。自建产品时，把 SAM 部署为后端服务、前端保留涂抹画布做兜底微调，是当前体验最好的组合。

## 多参考图 + 蒙版

典型场景：换装（第一张是人物原图，后面是款式 / 材质参考图，蒙版标记衣服区域）：

```python theme={null}
import base64
from openai import OpenAI

client = OpenAI(
    api_key="sk-your-api-key",
    base_url="https://api.apiyi.com/v1"
)

with open("person.png", "rb") as person, \
     open("clothes_reference.png", "rb") as clothes, \
     open("fabric_reference.png", "rb") as fabric, \
     open("mask.png", "rb") as mask:

    result = client.images.edit(
        model="gpt-image-2.5-sunburst",
        image=[person, clothes, fabric],
        mask=mask,
        prompt=(
            "第一张图片是需要编辑的主体。"
            "仅修改第一张图片蒙版透明区域中的衣服。"
            "使用第二张图片的服装款式和第三张图片的面料质感，"
            "保持第一张图片的人脸、发型、姿势、身体比例、背景和光线不变。"
        ),
        quality="high",
        size="1024x1536",
        output_format="png",
    )

with open("result.png", "wb") as output:
    output.write(base64.b64decode(result.data[0].b64_json))
```

<Tip>
  多图时必须在 prompt 中**清楚描述每张图的用途**（第一张是主体、第二张是款式参考、第三张是材质参考），否则模型可能混淆图片角色。
</Tip>

## 严格保持蒙版外不变（像素级后处理）

由于模型可能轻微修改蒙版外内容，对像素精度要求高的场景（商品图、证件版式、固定 UI 截图），可以在生成后把蒙版外区域强制替换回原图：

```python theme={null}
from PIL import Image, ImageFilter

original = Image.open("input.png").convert("RGBA")
edited = Image.open("edited.png").convert("RGBA")
mask = Image.open("mask.png").convert("RGBA")

if edited.size != original.size:
    edited = edited.resize(original.size, Image.Resampling.LANCZOS)

# 原蒙版 Alpha：0 = 编辑区，255 = 保留区
alpha = mask.getchannel("A")

# 反转后：255 = 使用编辑结果，0 = 使用原图
edit_area = alpha.point(lambda value: 255 - value)

# 轻微羽化，避免边缘太硬
edit_area = edit_area.filter(ImageFilter.GaussianBlur(radius=3))

final = Image.composite(edited, original, edit_area)
final.save("final.png")
```

最终效果：蒙版内部采用 AI 编辑结果，蒙版外部恢复成原始图片，边界轻微羽化融合。

## 常见错误排查

<AccordionGroup>
  <Accordion title="invalid_image_file / Invalid image file or mode">
    常见原因：

    * 蒙版不是有效 PNG，或文件内容损坏
    * 扩展名是 PNG，实际编码不是 PNG
    * 图片模式异常（CMYK、调色板模式、缺 Alpha）
    * 上传时 MIME 类型错误
    * 文件流在请求前已被读取完毕或关闭

    统一转码可解决大多数问题：

    ```python theme={null}
    from PIL import Image

    Image.open("input_source.jpg").convert("RGBA").save("input.png")
    Image.open("mask_source.png").convert("RGBA").save("mask.png")
    ```
  </Accordion>

  <Accordion title="原图和蒙版尺寸不一致">
    哪怕只差 1 像素也会报错。修正：

    ```python theme={null}
    from PIL import Image

    image = Image.open("input.png")
    mask = Image.open("mask.png").convert("RGBA")

    mask = mask.resize(image.size, Image.Resampling.NEAREST)
    mask.save("mask_fixed.png")
    ```
  </Accordion>

  <Accordion title="黑白蒙版没有 Alpha 通道">
    `RGB` / `L` / `P` 模式都不行，必须是 `RGBA`。用上文「方法二」把黑白蒙版转换成 Alpha 蒙版。
  </Accordion>

  <Accordion title="蒙版透明和输出透明是两回事">
    这是两个不同的透明，容易混：

    ```text theme={null}
    蒙版透明：标记「这块允许模型改」，由 mask 文件的 alpha 通道表达
    输出透明：成图本身带 alpha 通道，由 background 参数控制
    ```

    两者**可以同时用**：一边传带 alpha 的 `mask` 标记编辑区，一边传 `background: "transparent"` 让成图输出透明底，实测不冲突。

    输出透明要求 `output_format` 是 `png` 或 `webp`，配 `jpeg` 会 400（jpeg 没有 alpha 通道）。完整说明见 [怎么生成透明背景的图片](/faq/image-transparent-background)。
  </Accordion>

  <Accordion title="response_format=url 拿不到图">
    GPT Image 系列固定返回 Base64 数据，`response_format` 只适用于旧的 DALL·E 2 行为。正确读取方式：

    ```python theme={null}
    result.data[0].b64_json
    ```
  </Accordion>

  <Accordion title="Content-Type isn't multipart/form-data">
    通常是手动设置了 `Content-Type` 头导致 boundary 丢失，或中间层把 multipart 请求解析成 JSON 后再转发。让 HTTP 客户端自动生成 multipart 头即可。
  </Accordion>
</AccordionGroup>

## 尺寸参数

`gpt-image-2` 支持灵活尺寸，需同时满足：

```text theme={null}
宽和高都必须是 16 的倍数
最长边不超过 3840px
长宽比不超过 3:1
总像素不低于 655,360
总像素不超过 8,294,400
```

常用尺寸：`1024x1024`、`1536x1024`、`1024x1536`、`2048x2048`、`2048x1152`、`3840x2160`、`2160x3840`、`auto`。方形图片通常生成更快。

## 生产环境请求模板

```python theme={null}
result = client.images.edit(
    model="gpt-image-2.5-sunburst",
    image=open("input.png", "rb"),
    mask=open("mask.png", "rb"),
    prompt="""
仅编辑蒙版透明区域。

编辑任务：
把透明区域内的白色马克杯替换成一束白色郁金香。

必须保持：
- 原有构图不变
- 相机位置和镜头透视不变
- 桌面及背景不变
- 光线方向和色温不变
- 蒙版外人物和物品不变
- 输出保持真实摄影风格

融合要求：
花束需要自然位于原马克杯所在位置，产生符合现场光线的阴影、
反射和接触关系，不要增加其他物体。
""",
    size="1536x1024",
    quality="high",
    output_format="png",
)
```

## 相关页面

<CardGroup cols={2}>
  <Card title="图片编辑 API 参考" icon="image" href="/api-capabilities/gpt-image-2/image-edit">
    完整参数说明与在线调试 Playground
  </Card>

  <Card title="GPT-Image-2 概览" icon="sparkles" href="/api-capabilities/gpt-image-2/overview">
    模型能力、定价与版本说明
  </Card>
</CardGroup>

<Info>
  官方参考资料（请复制到浏览器访问）：

  * 模型说明：`developers.openai.com/api/docs/models/gpt-image-2.5-sunburst`、`developers.openai.com/api/docs/models/gpt-image-2`
  * 图像编辑 API Reference：`developers.openai.com/api/reference/python/resources/images/methods/edit/`
  * 图像生成指南：`developers.openai.com/api/docs/guides/image-generation`
</Info>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.