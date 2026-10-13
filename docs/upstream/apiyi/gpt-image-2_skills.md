> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# GPT-Image-2.5 / 2 系列 Agent 技能

> 把 gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2（官转）与 gpt-image-2.5-all / gpt-image-2-all / gpt-image-2-vip（官逆）封装成一个开箱即用的 Agent Skill，丢进 Codex、OpenClaw、hermes-agent、Claude Code 等任意编码 Agent，用 --model 一键切换六个模型完成文生图、多图融合与局部重绘。

<Note>
  本页提供一个**开箱即用的 Agent 技能（Skill）**：一个脚本同时覆盖 **gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2（官转）** 与 **gpt-image-2.5-all / gpt-image-2-all / gpt-image-2-vip（官逆）** 六个模型——它们都走同一套 OpenAI Images API，只是 `--model` 不同。把它放进你正在用的编码 Agent，一句话即可出图，整套东西就两个文件。
</Note>

## 这个技能能做什么

一个合并技能，脚本会根据**是否传入图片**自动判断走「文生图」还是「图片编辑」：

<CardGroup cols={3}>
  <Card title="文生图" icon="wand-sparkles">
    只给提示词 → 生成全新图片，文字渲染、写实质感强。
  </Card>

  <Card title="多图融合" icon="layers">
    传入多张图（最多 16 张）+ 一条指令 → 把图1的人放进图2的场景、保留图3的风格等。
  </Card>

  <Card title="局部重绘" icon="image">
    传入一张图 + `--mask` 掩码 + 指令 → 只改掩码覆盖的区域（**仅官转三款支持**，推荐 `gpt-image-2.5-sunburst`）。
  </Card>
</CardGroup>

## 六个模型怎么选

六个模型**调用方式完全一致**，区别只在通道来源、价格/速度和支持的参数。官转三款（2.5-flare / 2.5-sunburst / 2）同价同参数，官逆三款同价同参数。脚本已按 `--model` 自动处理这些差异：

| 模型（`--model`） | 通道 | 价格 | 速度 | `size` | `quality` / `mask` | 适合 |
| - | - | - | - | - | - | - |
| `gpt-image-2.5-flare`（默认） | 官转（官方直连） | 按量 \~\$0.03–0.2/张 | **官转最快**（1K low 实测约 10s） | ✅ 任意预设 | ✅ 支持，含 `xhigh` / `max` | 日常文生图默认、要画质档、要透明背景 |
| `gpt-image-2.5-sunburst` | 官转（官方直连） | 按量，同 flare | 比 flare 慢 | ✅ 任意预设 | ✅ 支持，含 `xhigh` / `max` | 改图 / 多图融合 / 掩码局部重绘，编辑精度最高 |
| `gpt-image-2` | 官转（官方直连） | 按量，同 flare | \~100–120s | ✅ 任意预设 | ✅ 支持（到 `high`） | 上一代，存量项目沿用 |
| `gpt-image-2.5-all` | 官逆（ChatGPT 网页版 2.5） | flat \$0.025/张 | 约 30–90s | ❌ 写进 prompt | ❌ | 走量、要快、要 2.5 画质，尺寸用提示词描述即可 |
| `gpt-image-2-all` | 官逆（ChatGPT 线） | flat \$0.025/张 | **最快 \~30–60s** | ❌ 写进 prompt | ❌ | 走量、要快、尺寸用提示词描述即可 |
| `gpt-image-2-vip` | 官逆（Adobe 线） | flat \$0.03/张 | \~90–150s | ✅ 30 档含 **4K** | ❌ | 要锁定输出尺寸 / 4K，价格还想便宜 |
| `gpt-image-2.5-flare-vip` / `gpt-image-2.5-sunburst-vip` | 官逆（Adobe 线 2.5） | flat \$0.03/张 | \~20–140s | ✅ 30 档含 **4K** | `quality` 六档全开（含 `xhigh` / `max`）、透明背景 ✅；`mask` 只整图重绘 ❌ | 便宜价拿到 2.5 画质，尺寸可锁 |

<Tip>
  简单记：**日常文生图** → `gpt-image-2.5-flare`；**改图 / 掩码局部重绘** → `gpt-image-2.5-sunburst`；**要快又便宜** → `gpt-image-2.5-all` / `gpt-image-2-all`；**要锁尺寸/4K 又便宜** → `gpt-image-2-vip`。
</Tip>

## 能用在哪些 Agent

<Info>
  一个 Skill 本质上就是**一个文件夹**：一份写给 Agent 看的说明（`SKILL.md`）+ 一个干活的脚本。所以**凡是能读取本地文件、执行命令行的编码 Agent 都能用上**——比如 **Codex、OpenClaw、hermes-agent、Claude Code** 等。

  唯一要求：跑 Agent 的那台机器（你的电脑或服务器）装了 **Python 3** 并且**能联网**（脚本要直连 `api.apiyi.com`）。仅此而已，不挑具体哪家 Agent。
</Info>

## 三步装好

### ① 建目录、贴文件、装依赖

新建一个技能文件夹，放入下面两个文件（完整内容见后两节）。本技能用 OpenAI SDK 调用，需要先装一个依赖：

```bash theme={null}
pip install openai
```

```
gpt-image-2/
├── SKILL.md
├── scripts/
│   └── gpt_image.py
└── .env          # 第②步创建，放你的 Key
```

### ② 同目录写 Key

在 `gpt-image-2/.env` 里写上你的 **API易 API Key**（在 `api.apiyi.com` 控制台创建）：

```bash theme={null}
APIYI_API_KEY=sk-your-api-key
```

脚本会自动从这个 `.env` 读取 Key，**无需任何额外配置或环境变量**。

<Warning>
  `.env` 里是你的密钥。如果这个技能要随项目仓库共享，**务必把 `.env` 加进 `.gitignore`，不要提交到 git**。
</Warning>

### ③ 交给 Agent

* **支持技能自动发现的 Agent**（如 Claude Code）：把整个 `gpt-image-2/` 目录放进它的技能目录——个人级 `~/.claude/skills/`，或项目级 `.claude/skills/`（随仓库共享）。
* **其他 Agent**：按它各自的技能/插件约定放置；或者最简单——**直接让 Agent「读一下这个文件夹里的 SKILL.md，并照着执行」** 即可。

装好后就能用了，跳到 [怎么用](#怎么用) 看示例。

## SKILL.md

新建 `gpt-image-2/SKILL.md`，完整内容如下（`description` 写清「做什么 + 何时用」，Agent 会据此自动触发）：

````markdown theme={null}
---
name: gpt-image-2
description: Generate or edit images via APIYI's gpt-image-2 (official) and gpt-image-2-all / gpt-image-2-vip (reverse) models. Use this when the user asks to create, draw, render, or generate an image/illustration/poster, or to edit, retouch, restyle, fuse, or inpaint existing images.
allowed-tools: Bash(python3 *)
---

# GPT-Image-2 系列出图技能

通过 API易 平台调用 GPT-Image 2.5 / 2 系列生成或编辑图片。一个脚本覆盖六个模型，用 `--model` 切换：

- `gpt-image-2.5-flare`（默认，官转）：速度优先，支持 `size` / `quality`（含 `xhigh` / `max`）/ `mask` 局部重绘，按量计费。
- `gpt-image-2.5-sunburst`（官转）：编辑精度优先，参数同 flare，改图 / 多图融合首选。
- `gpt-image-2`（官转）：上一代，参数同上（`quality` 只到 `high`）。
- `gpt-image-2.5-all`（官逆）：ChatGPT 网页版 2.5 逆向，flat \$0.025/张，参数同 `-all`。
- `gpt-image-2.5-flare-vip` / `gpt-image-2.5-sunburst-vip`（官逆·Adobe 线 2.5）：flat \$0.03/张，可锁 `size`，支持 `quality` 六档（含 `xhigh` / `max`）与透明背景；`mask` 只整图重绘。
- `gpt-image-2-all`（官逆·ChatGPT 线）：最快、flat \$0.025/张；**不支持 `size`/`quality`**，尺寸写进提示词。
- `gpt-image-2-vip`（官逆·Adobe 线）：可锁 `size`（30 档含 4K）、flat \$0.03/张；**不支持 `quality`/`mask`**。

## Key 配置

脚本会自动读取技能目录下 `.env` 文件里的 `APIYI_API_KEY`（也支持同名环境变量）。
脚本依赖 OpenAI SDK，若报缺包，提示用户 `pip install openai`。

## 用法

第一个参数是提示词；编辑/融合时用 `-i` 传入一张或多张本地图片：

```bash
# 文生图（默认 gpt-image-2.5-flare 官转，可带画质）
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "一只戴墨镜的橘猫坐在海边吧台，电影画幅" -o cat.png --size 1536x1024 --quality high

# 最快、最省：用官逆 all（尺寸写进提示词，别传 size）
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "扁平插画风节日海报，竖版 2:3" -o poster.png --model gpt-image-2-all

# 要锁 4K：用官逆 vip
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "城市夜景航拍" -o city.png --model gpt-image-2-vip --size 3840x2160

# 多图融合（最多 16 张，重复 -i）
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "把图1的人物放进图2的场景，保留图3的色彩风格" -i person.png -i scene.png -i style.png -o fused.png

# 局部重绘（掩码，仅官转三款；推荐 gpt-image-2.5-sunburst）
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "把蒙版区域换成一扇圆窗" -i room.png --mask mask.png -o edited.png

# 一次出多张（最多 5 张，并发）
python3 ${CLAUDE_SKILL_DIR}/scripts/gpt_image.py "节日海报草图" -o draft.png -n 3 --model gpt-image-2-all
```

参数说明：

- 第 1 个位置参数：提示词（必填）。
- `--model`：`gpt-image-2`（默认）/ `gpt-image-2-all` / `gpt-image-2-vip`。也可在 `.env` 写 `APIYI_IMAGE_MODEL=...` 设默认。
- `-i / --image`：输入图片路径，可重复（最多 16 张）；不传 = 文生图，传 = 编辑/融合。
- `-o / --out`：输出文件名，默认 `output.png`。
- `-n / --count`：一次出几张，**默认 1**，最多 5（客户端并发；脚本一律不传 `n`，避免官逆通道按张数超扣费）。
- `--size`：尺寸，默认 `auto`（`gpt-image-2-all` 会忽略，请把尺寸/比例写进提示词）。
- `--quality`：`low`/`medium`/`high`/`xhigh`/`max`/`auto`，**官转三款与 2.5 两款 -vip 生效**（`xhigh` / `max` 只有 2.5 系列接受；脚本对 `-all` / `gpt-image-2-vip` 自动不传）。
- `--format`：`png`/`jpeg`/`webp`，仅官转生效。
- `--mask`：掩码图，仅官转编辑生效（PNG 带 alpha，对第一张图）。
- `--background`：`transparent`/`opaque`/`auto`，仅官转生效。传 `transparent` 时 `--format` 必须是 `png` 或 `webp`。

## 选型与红线（重要）

- **默认 `gpt-image-2.5-flare`（官转）**：日常文生图；改图 / 掩码局部重绘换 `gpt-image-2.5-sunburst`。
- 要**快/便宜** → `gpt-image-2-all`；要**锁尺寸/4K** → `gpt-image-2-vip`。
- 官逆 `-all` / `gpt-image-2-vip` **不要传 `quality`**（2.5 两款 -vip 可传）；**`gpt-image-2-all` 不要传 `size`**（写进提示词）。脚本已自动门控，但你直接调脚本时也请遵守。
- **透明背景**仅官转三款有 `background` 参数：加 `--background transparent` 即可出带 alpha 通道的透明底图（`--format` 需为 `png`/`webp`）。官逆 all/vip 没有这个参数，只能在提示词里要求，偶现不稳定。

## 出图张数与成本

- **默认只出 1 张**；用户明确要多张才用 `-n`，一次不超过 5。
- 官逆 -all 系列 flat \$0.025/张、-vip 系列 flat \$0.03/张；官转三款按量、`--quality high` 较贵（1K 档约 \$0.05～0.21/张，`xhigh` / `max` 更贵），预算敏感就降 `medium`/`low` 或改用官逆。

## 输出位置（重要）

- `-o` 传**纯文件名**时存到**项目根目录下的 `gpt-image-output/`**；传**带目录路径**时按给定路径存。
- 不要把图片写到 `/tmp`、scratchpad 等临时目录，用户会找不到。

## 完成后

脚本会为每张图打印一行完整路径，如实回报给用户。若被内容审核拒绝，把原因如实转达，不要重试同一提示词。
````

<Tip>
  `name` 必须是小写字母 + 连字符。在支持斜杠命令的 Agent 里，目录名就是命令名——叫 `gpt-image-2` 即 `/gpt-image-2`。`${CLAUDE_SKILL_DIR}` 是 Claude Code 提供的技能目录变量；其他 Agent 直接用脚本的实际路径即可。
</Tip>

## scripts/gpt\_image.py

新建 `gpt-image-2/scripts/gpt_image.py`，使用 OpenAI SDK 指向 API易（`base_url="https://api.apiyi.com/v1"`）：

```python theme={null}
#!/usr/bin/env python3
"""通过 API易 调用 GPT-Image 2.5 / 2 系列（gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2 官转，gpt-image-2.5-all / gpt-image-2-all / gpt-image-2-vip 官逆）生成 / 编辑图片。
都走 OpenAI Images API（/v1/images/generations + /v1/images/edits），用 --model 切换。需要：pip install openai"""
import argparse
import base64
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from openai import OpenAI

# 一次调用最多并发出几张图（服务端 n 只出 1 张，这里用客户端并发模拟多张）
MAX_COUNT = 5

# 各模型能力门控：是否接受这些参数（不接受的一律不传，避免报错或超扣费）
MODEL_CAPS = {
    "gpt-image-2.5-flare":    {"size": True,  "quality": True,  "output_format": True,  "mask": True,  "background": True},   # 官转·速度优先
    "gpt-image-2.5-sunburst": {"size": True,  "quality": True,  "output_format": True,  "mask": True,  "background": True},   # 官转·编辑优先
    "gpt-image-2":     {"size": True,  "quality": True,  "output_format": True,  "mask": True,  "background": True},   # 官转·上一代
    "gpt-image-2.5-all": {"size": False, "quality": False, "output_format": False, "mask": False, "background": False},  # 官逆·ChatGPT 2.5
    "gpt-image-2-all": {"size": False, "quality": False, "output_format": False, "mask": False, "background": False},  # 官逆·ChatGPT
    "gpt-image-2-vip": {"size": True,  "quality": False, "output_format": False, "mask": False, "background": False},  # 官逆·Adobe
    "gpt-image-2.5-flare-vip":    {"size": True,  "quality": True,  "output_format": False, "mask": False, "background": True},   # 官逆·Adobe 2.5（quality 六档全开；mask 不做局部重绘）
    "gpt-image-2.5-sunburst-vip": {"size": True,  "quality": True,  "output_format": False, "mask": False, "background": True},   # 官逆·Adobe 2.5
    "gpt-image-2.5-vip":          {"size": True,  "quality": True,  "output_format": False, "mask": False, "background": True},   # = sunburst-vip 别名
}


def caps_of(model):
    # 未知模型回落到官转能力集
    return MODEL_CAPS.get(model, MODEL_CAPS["gpt-image-2.5-flare"])


def load_api_key():
    """优先读环境变量；否则在脚本所在目录及其父目录找 .env。"""
    key = os.environ.get("APIYI_API_KEY")
    if key:
        return key
    here = os.path.dirname(os.path.abspath(__file__))
    for d in (here, os.path.dirname(here)):
        env_path = os.path.join(d, ".env")
        if os.path.exists(env_path):
            with open(env_path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("APIYI_API_KEY") and "=" in line:
                        return line.split("=", 1)[1].strip().strip('"').strip("'")
    return None


def project_root():
    """从脚本位置向上找包含 .git 或 .claude 的目录，作为项目根目录；找不到则用当前工作目录。"""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isdir(os.path.join(d, ".git")) or os.path.isdir(os.path.join(d, ".claude")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.getcwd()
        d = parent


def resolve_paths(out, count):
    """决定输出路径列表。纯文件名 → 存到 <项目根>/gpt-image-output/；带目录则按给定路径。"""
    if os.path.dirname(out):
        base_path = os.path.abspath(out)
    else:
        out_dir = os.path.join(project_root(), "gpt-image-output")
        os.makedirs(out_dir, exist_ok=True)
        base_path = os.path.join(out_dir, out)

    if count == 1:
        return [base_path]
    base, ext = os.path.splitext(base_path)
    return [f"{base}-{i}{ext}" for i in range(1, count + 1)]


def decode_image(item):
    """统一取图字节：b64_json 可能是纯 base64 或带 data:image 前缀的 data URL（官逆模型）；也可能只给 url。"""
    raw = getattr(item, "b64_json", None)
    if raw:
        if raw.startswith("data:"):
            raw = raw.split(",", 1)[1]  # 剥掉 data:image/png;base64, 前缀
        return base64.b64decode(raw)
    url = getattr(item, "url", None)
    if url:
        with urllib.request.urlopen(url, timeout=360) as r:
            return r.read()
    raise RuntimeError("响应里既没有 b64_json 也没有 url")


def one_image(client, model, args):
    """发一次请求，返回图片字节；失败抛异常（由 _safe 兜住）。一律不传 n（默认 1 张，多张靠客户端并发）。"""
    cap = caps_of(model)
    if args.image:
        # 编辑 / 多图融合：每次重新 open 文件，避免线程间共享句柄
        files = [open(p, "rb") for p in args.image]
        try:
            kwargs = dict(model=model, image=files if len(files) > 1 else files[0], prompt=args.prompt)
            if cap["size"] and args.size:
                kwargs["size"] = args.size
            if cap["quality"] and args.quality:
                kwargs["quality"] = args.quality
            if cap["output_format"] and args.format:
                kwargs["output_format"] = args.format
            if cap["background"] and args.background and args.background != "auto":
                kwargs["background"] = args.background
            if cap["mask"] and args.mask:
                kwargs["mask"] = open(args.mask, "rb")
            resp = client.images.edit(**kwargs)
        finally:
            for fh in files:
                fh.close()
    else:
        # 文生图
        kwargs = dict(model=model, prompt=args.prompt)
        if cap["size"] and args.size:
            kwargs["size"] = args.size
        if cap["quality"] and args.quality:
            kwargs["quality"] = args.quality
        if cap["output_format"] and args.format:
            kwargs["output_format"] = args.format
        if cap["background"] and args.background and args.background != "auto":
            kwargs["background"] = args.background
        resp = client.images.generate(**kwargs)
    return decode_image(resp.data[0])


def main():
    api_key = load_api_key()
    if not api_key:
        sys.exit("未找到 API Key：请在技能目录的 .env 写一行 APIYI_API_KEY=sk-xxx")

    default_model = os.environ.get("APIYI_IMAGE_MODEL", "gpt-image-2.5-flare")
    # 同步阻塞调用，图片生成慢，超时给足 360s
    client = OpenAI(api_key=api_key, base_url="https://api.apiyi.com/v1", timeout=360)

    parser = argparse.ArgumentParser(description="GPT-Image 2.5 / 2 系列出图")
    parser.add_argument("prompt", help="提示词 / 编辑指令")
    parser.add_argument("--model", default=default_model,
                        help="gpt-image-2.5-flare(官转·默认) / gpt-image-2.5-sunburst(官转·编辑) / gpt-image-2(官转·上一代) / gpt-image-2.5-all / gpt-image-2-all(官逆·最快) / gpt-image-2-vip(官逆·可锁尺寸) / gpt-image-2.5-flare-vip / gpt-image-2.5-sunburst-vip(官逆·2.5·可锁尺寸+quality)")
    parser.add_argument("-i", "--image", action="append", default=[],
                        help="输入图片路径（可重复，最多 16 张；传入即为编辑/融合模式）")
    parser.add_argument("-o", "--out", default="output.png", help="输出文件名")
    parser.add_argument("-n", "--count", type=int, default=1,
                        help=f"一次出几张，默认 1，最多 {MAX_COUNT}（客户端并发）")
    parser.add_argument("--size", default="auto",
                        help="尺寸，如 1024x1024 / 2048x1152 / auto（gpt-image-2-all 不支持，写进 prompt）")
    parser.add_argument("--quality", default="high",
                        help="画质 low / medium / high / xhigh / max / auto（仅官转生效；xhigh/max 仅 2.5）")
    parser.add_argument("--format", default="png", help="输出格式 png / jpeg / webp（仅官转生效）")
    parser.add_argument("--mask", help="掩码图（仅官转编辑，PNG 带 alpha，对第一张图生效）")
    parser.add_argument("--background", default="auto", choices=["transparent", "opaque", "auto"],
                        help="背景，transparent 出透明底（仅官转生效，需配 --format png/webp）")
    args = parser.parse_args()

    # jpeg 没有 alpha 通道，与透明背景互斥，先在本地拦掉，别把 400 甩给用户
    if args.background == "transparent" and args.format == "jpeg":
        sys.exit("--background transparent 不能配 --format jpeg（jpeg 无 alpha 通道），请改用 png 或 webp")

    count = args.count
    if count < 1:
        count = 1
    if count > MAX_COUNT:
        print(f"提示：一次最多 {MAX_COUNT} 张，已将 {args.count} 限制为 {MAX_COUNT}。", file=sys.stderr)
        count = MAX_COUNT

    paths = resolve_paths(args.out, count)

    def task(path):
        data = one_image(client, args.model, args)
        with open(path, "wb") as f:
            f.write(data)
        return os.path.abspath(path)

    failures = 0
    with ThreadPoolExecutor(max_workers=count) as pool:
        for path, result in zip(paths, pool.map(lambda p: _safe(task, p), paths)):
            ok, value = result
            if ok:
                print(f"图片已保存至 {value}")
            else:
                failures += 1
                print(f"第 {os.path.basename(path)} 张生成失败：{value}", file=sys.stderr)

    if failures == count:
        sys.exit("全部生成失败。")


def _safe(fn, arg):
    try:
        return True, fn(arg)
    except Exception as e:  # noqa: BLE001 — 单张失败不影响其它并发任务
        return False, str(e)


if __name__ == "__main__":
    main()
```

## 怎么切换模型

切换模型**只需改 `--model`**，六个值任选：

```text theme={null}
... gpt_image.py "提示词"                              # 默认 gpt-image-2.5-flare（官转·速度优先）
... gpt_image.py "改成水彩" -i in.png --model gpt-image-2.5-sunburst   # 官转·编辑精度优先
... gpt_image.py "提示词" --model gpt-image-2            # 官转·上一代
... gpt_image.py "提示词" --model gpt-image-2.5-all      # 官逆·ChatGPT 网页版 2.5
... gpt_image.py "提示词" --model gpt-image-2-all      # 官逆·最快·最省
... gpt_image.py "提示词" --model gpt-image-2-vip --size 3840x2160   # 官逆·锁 4K
```

想改默认通道（不每次都加 `--model`），在 `gpt-image-2/.env` 里加一行：

```bash theme={null}
APIYI_IMAGE_MODEL=gpt-image-2-all
```

<Info>
  脚本已**自动兼容** base64 与 url 两种返回（官逆模型的 `b64_json` 带 `data:image;base64,` 前缀，脚本会自动剥除）。只有当你**强依赖 URL 输出**时，才需要在 API易 控制台把令牌「分组」切到 `image2_OSS`——普通出图无需关心。
</Info>

## 为什么一句话就能出图

很多人好奇：我又没敲命令，怎么说句"画只猫"它就出图了？

原理是这样：Agent 启动时会**先读取每个技能 `SKILL.md` 里的 `description`**（一段很短的元数据，说明「这个技能做什么、什么时候该用」）。当你说出的需求**匹配上**这段描述的场景（比如"画/生成/渲染一张图""把这几张图融合一下"），Agent 就**自动决定调用这个技能**，去读完整的 `SKILL.md` 并运行脚本——整个过程你不用记任何命令。

不想靠 Agent 猜、想要**百分百可控**时，用下面的**显性调用**。

## 怎么用

### 自然语言（隐式触发）

装好后直接对 Agent 说话即可：

| 你说 | 技能行为 |
| - | - |
| "画一张电影画幅的猫" | 默认 gpt-image-2.5-flare，出 1 张 png |
| "快点、便宜点出张海报" | Agent 会带 `--model gpt-image-2-all` |
| "出一张 4K 的城市夜景" | Agent 会带 `--model gpt-image-2-vip --size 3840x2160` |
| "把 person.png 的人放进 scene.png" | 调 `-i person.png -i scene.png`，融合出图 |

### 显性调用（更可控）

* **支持斜杠命令的 Agent**（如 Claude Code）：

  ```text theme={null}
  /gpt-image-2 赛博朋克城市雨夜，霓虹招牌特写 --model gpt-image-2-vip --size 2048x1152
  ```

* **任意 Agent / 直接命令它跑脚本**（最通用）：

  ```text theme={null}
  运行 python3 gpt-image-2/scripts/gpt_image.py "赛博朋克城市雨夜，霓虹招牌特写" --model gpt-image-2-all
  ```

## 生成的图片在哪里

* 当 `-o` 只传**文件名**（如 `-o cat.png`）时，图片统一存到**项目根目录下的 `gpt-image-output/` 文件夹**（脚本自动创建），所以你在项目里的这个文件夹就能直接找到。
* 「项目根目录」= 脚本从自身位置向上找到的第一个含 `.git` 或 `.claude` 的目录——**不管 Agent 在哪个目录运行，图都落在项目里**，不会跑进临时目录害你找不到。
* 脚本会**为每张图打印一行完整绝对路径**，例如 `图片已保存至 /Users/you/project/gpt-image-output/cat.png`。
* 默认**只出 1 张**；`-n 3` 一次出 3 张（最多 5），文件名自动加 `-1`、`-2`、`-3` 后缀。
* 传**带目录的路径**（如 `-o images/cat.png` 或绝对路径）时，按你给的路径存，不进 `gpt-image-output/`。
* 编辑 / 融合同理：输出是新文件，**不会覆盖你的原图**。

## 相关文档

* [GPT-Image-2-All Agent 技能](/api-capabilities/gpt-image-2-all/skills)（官逆·最快）
* [GPT-Image-2-VIP Agent 技能](/api-capabilities/gpt-image-2-vip/skills)（官逆·锁 4K）
* [GPT-Image-2 图片生成总览](/api-capabilities/gpt-image-2/overview)
* [Nano Banana Pro Agent 技能](/api-capabilities/nano-banana-image/skills)


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.