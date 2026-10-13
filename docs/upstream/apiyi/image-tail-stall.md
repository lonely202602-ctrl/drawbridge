> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 出图请求传完却不返回的兼容处理

> 图片其实已经完整传完，但连接迟迟不结束，客户端一直等到自己超时。说明成因、判别方法，并给出在客户端主动收尾的兼容代码 —— 是在现有调用上加一层保护，不是替换调用方式。

<Info>
  **一句话结论**：图片数据**是完整的**，能正常解码出图，卡住的只是 HTTP 传输的最后一个动作 ——「告诉客户端传完了」。所以正确的处理不是把超时调长、也不是重试，而是**在数据已经到齐时主动收尾，把图取出来用**。
</Info>

<Warning>
  ### 这是**兼容**，不是**替换**

  下面给的代码是在你**现有调用逻辑之外加一层保护**，不是让你换一套接入方式：

  * **不需要**换端点、换模型、换 SDK，也不需要改请求参数；
  * 正常请求走的还是原来的路径，**行为完全不变** —— 这段兼容逻辑在正常请求下根本不会触发；
  * 只有当「数据已经到齐、但连接迟迟不结束」时，它才会介入，把已经拿到的图交给你。

  换句话说：\*\*加上它，坏的情况能救回来；不加它，坏的情况只能等到超时报错。\*\*其余一切照旧。
</Warning>

## 现象

调用原生出图接口（`POST /v1beta/models/{model}:generateContent`）时，可能遇到这样一组现象：

* 后台调用日志显示请求**成功**、也**已经计费**；
* 客户端却一直挂着，直到自己的读超时才报错；
* 报错形如 `Read timed out`、`ETIMEDOUT`、`UND_ERR_BODY_TIMEOUT`。

体感就是「后台日志里 30 秒就好了，我这边 5 分钟都拿不到图」。

<Warning>
  **同一套代码以前一直是好用的，现在会在收尾这一步卡住不放。** 这是近期新出现的场景，不是你的集成方式一直有问题 —— 所以你不需要怀疑自己的调用写法，只需要按下面的方式加一层兼容。
</Warning>

### 它是成时间窗发作的

这一点很重要，直接决定你怎么复现、怎么判断：

* **窗内**：连续多次调用**全部**卡住，不分先后；
* **窗外**：几十次连续调用**一次都不出现**，完全正常。

所以它既不是「必现」，也不是「小概率偶发」。如果你测的时候刚好错开了窗口，会 100% 正常，很容易得出「已经好了」的错误结论。反过来，如果你正好撞进窗口，会觉得「全挂了」。**两种体感都是真的，别用其中一次的结果去下长期结论。**

<Info>
  **流式请求**（`:streamGenerateContent`）和纯文本模型一般不受影响。本页针对的是**非流式出图**这一类响应体很大的请求 —— 一张 2K 图的 JSON 响应体在 13 MB 量级。
</Info>

## 成因

出图响应用 `Transfer-Encoding: chunked` 分块传输。按 HTTP/1.1 规范，服务端把最后一个数据块发完之后，还必须再发一个**终止块**（长度为 0 的块），用来告诉客户端「到此为止，传完了」。

问题就出在这一步：**数据块全部到齐了，终止块却没有发出来，连接也没有关闭。**

于是客户端手里握着一份**完整可用的 JSON**（图片能正常 base64 解码），但它无从知道这份数据已经收完了，只能继续等 —— 一直等到自己的读超时。

打个比方：**快递已经放到你门口了，但快递员忘了点「已送达」。** 你守在系统前等状态更新，东西其实就在门外。

<Warning>
  链路在某些时间窗内没有给响应做这个收尾动作。**服务端侧的根治我们会继续推进**，本页给的是在此之前的**客户端兜底方案**。

  这层兜底有它自己独立的价值，而且**不需要等服务端修好再回滚**：有结束信号时它永远不会被触发，服务端修复之后会自动静默，零开销、零维护负担。
</Warning>

三个关键判断，直接决定该怎么处理：

<CardGroup cols={3}>
  <Card title="数据是完整的" icon="circle-check">
    不是丢包、不是网络质量问题、更不是传到一半断了。已收到的字节能完整解析，图片可以正常使用。
  </Card>

  <Card title="继续等没有意义" icon="timer-off">
    卡住之后服务端**一个字节都不会再发**。实测持续等待 **330 秒**仍无任何变化，把超时调到几百秒只是白白拖长故障感知时间。
  </Card>

  <Card title="不绑定某台机器" icon="server-off">
    故障窗内多个落点**同时**出现、又**同时**恢复，所以换域名、换入口都绕不开，只能在客户端处理。
  </Card>
</CardGroup>

## 判别方法

同时满足下面三条，基本可以确定就是这个场景：

<Steps>
  <Step title="响应头带 Transfer-Encoding: chunked，且没有 Content-Length">
    这说明响应体的长度不是预先声明的，客户端只能靠终止块判断「传完了」。
  </Step>

  <Step title="已收到的字节能被完整解析成 JSON">
    把已收字节做一次 `json.loads`，能成功；并且里面的 `inlineData.data` 做 base64 解码后是一张完整可用的图片。
  </Step>

  <Step title="解析成功之后，连接长时间没有任何新字节">
    既没有收到终止块，连接也没有被关闭 —— 它就那样一直开着。
  </Step>
</Steps>

### 与另外两种形态的区别

三种情况报错很像，但根因和处理方式完全不同，**不要混用同一套判据**：

| 观测项 | 本页场景（无限期挂住） | 被掐断（`ECONNRESET`） | 收尾迟到后被断开 |
| - | - | - | - |
| 已收字节数 | **= 全量，JSON 可完整解析** | 通常不足全量 | = 全量 |
| 连接终态 | **既无终止块、也无 FIN，一直开着** | 收到 TCP RST | FIN（优雅关闭） |
| 失败时刻 | 无限期，等到客户端自己放弃（实测 330 秒仍无变化） | 不定 | last-byte 之后 **+300 秒** |
| 该怎么办 | **把已拿到的图直接用掉**（本页） | 排查网络路径与客户端 | 同本页，数据也是完整的 |

如果你的报错是 `ECONNRESET`，那属于另一类问题，判别方式见[连接中断排查](/api-capabilities/image-connection-drops)。

## 兼容改造：客户端主动收尾

思路很简单：**不要死等连接结束，而是在已收数据能被完整解析时就主动收尾。**

### 关键：必须留一个宽限期

不能一解析成功就立刻收尾。正常情况下，终止块往往就在下一个 TCP 分段里，只差几毫秒。如果解析成功就马上断开，会把「终止块晚到几毫秒」误判成「服务端没发」。

正确做法是：解析成功后**再等一小段时间**（建议 3\~5 秒）。这期间收到任何字节就按正常流程继续走；等不到才判定为卡住并主动收尾。

<Warning>
  **这一步不是可选优化。** 省掉宽限期会让判定完全失真 —— **每一个正常请求都会被误判成故障**。我们在实测中第一版就踩了这个坑，一整批正常请求全被误报。
</Warning>

### 收尾前要过的几道判定

按从便宜到昂贵的顺序排，**任何一条不满足就继续等，不要收尾**：

| # | 判定 | 为什么 |
| - | - | - |
| 1 | 连接仍处于「接收中」，没有正常结束 | 已经正常结束的走原有成功路径即可 |
| 2 | HTTP 状态码是 2xx | 非 2xx 交给你原有的错误处理 |
| 3 | 响应头没有 `Content-Length` | 有明确长度就说明不是这个场景，交给客户端库自己结束 |
| 4 | 已收数据达到一个最小体积 | 挡掉小体积的错误响应 |
| 5 | 数据量相比上次尝试有变化 | 避免对十几 MB 的内容反复做无谓解析 |
| 6 | 最后一个非空白字符是 `}` | 极便宜的预筛。图片的 base64 编码里不含 `}`，传输没到头几乎必定在这里被挡掉 |
| 7 | 完整 JSON 解析成功 | 最终裁决 |

第 6、7 条合起来让**误判概率接近于零**：响应是单个 JSON 对象，数据没收全时解析必然失败。换句话说，**只有真的收全了才可能收尾**。

### Python 实现

用后台线程做流式读取，主线程靠**队列超时**来实现宽限期：

```python theme={null}
import json
import time
import base64
import queue
import threading
import requests

MIN_BYTES = 1024          # 判定 4：比这还小不可能是一张图


def _pump(raw, q):
    """后台线程：只负责把收到的块塞进队列。"""
    try:
        for chunk in raw.stream(65536, decode_content=True):
            q.put(chunk)
        q.put(None)                       # 服务端正常收尾了
    except Exception as exc:              # 传输异常，交给主线程抛
        q.put(exc)


def _try_parse(buf):
    if len(buf) < MIN_BYTES:                    # 判定 4
        return None
    if not buf.rstrip().endswith(b"}"):         # 判定 6：极便宜的预筛
        return None
    try:
        return json.loads(buf.decode("utf-8"))  # 判定 7：最终裁决
    except ValueError:
        return None


def generate_image(url, headers, payload,
                   ttfb_timeout=180, term_grace=5, body_timeout=60):
    """非流式出图，带主动收尾。

    ttfb_timeout: 等首字节。这一段是上游在生成，慢不等于故障，要留够。
    term_grace:   数据到齐后，再等多久结束信号。等不到就主动收尾。
    body_timeout: 首字节之后，整个响应体最多再花多久。
    """
    resp = requests.post(url, headers=headers, json=payload,
                         stream=True, timeout=(10, ttfb_timeout))
    resp.raise_for_status()                     # 判定 2

    if resp.headers.get("Content-Length"):      # 判定 3
        return resp.json()                      # 长度已声明，交给库自己结束

    q = queue.Queue()
    threading.Thread(target=_pump, args=(resp.raw, q), daemon=True).start()

    buf = bytearray()
    deadline = time.monotonic() + body_timeout

    while True:
        try:
            item = q.get(timeout=term_grace)
        except queue.Empty:                     # 宽限期内没有任何新数据
            obj = _try_parse(buf)
            if obj is not None:
                resp.close()                    # 数据已到齐，主动收尾
                return obj
            if time.monotonic() > deadline:
                raise TimeoutError("响应不完整，且长时间没有新数据")
            continue                            # 还不完整，继续等

        if item is None:                        # 正常路径：服务端自己收了尾
            break
        if isinstance(item, Exception):
            raise item
        buf += item

    obj = _try_parse(buf)
    if obj is None:
        raise ValueError("响应不完整")
    return obj


def extract_image(obj):
    for cand in obj.get("candidates", []):
        for part in cand.get("content", {}).get("parts", []):
            if "inlineData" in part:
                return base64.b64decode(part["inlineData"]["data"])
    return None
```

<Warning>
  **为什么要多起一个线程？** 因为 `requests` 的读超时**只有一个值**，它同时管着「等首字节」和「块间等待」这两段。而卡住时读循环会一直阻塞在下一次读上，宽限期根本没有机会跑 —— 直接写成 `for chunk in ...` 加计时的版本，在真正卡住时是**不会触发**的。

  用后台线程读、主线程 `q.get(timeout=term_grace)`，才能把这两段超时真正拆开。这个坑我们自己踩过：一个超时值管两件事，会把「生成慢」和「不收尾」混成同一种失败，根本没法归因。
</Warning>

<Note>
  这个写法只在**宽限期到点时解析一次**，而不是每收到一块就试一次，天然满足上表第 5 条 —— 十几 MB 的内容不会被反复解析。
</Note>

### Node.js 实现

Node.js 这边不需要额外线程 —— `reader.read()` 本身就是 Promise，用 `Promise.race` 就能给「等下一块」加上宽限期上限：

```javascript theme={null}
const TERM_GRACE_MS = 5000;       // 数据到齐后再等多久结束信号
const TOTAL_TIMEOUT_MS = 180000;  // 总超时：必须留够上游生成的时间
const MIN_BYTES = 1024;

async function generateImage(url, headers, payload) {
  const resp = await fetch(url, {
    method: "POST",
    headers: { ...headers, "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(TOTAL_TIMEOUT_MS),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);        // 判定 2
  if (resp.headers.get("content-length")) return resp.json();  // 判定 3

  const reader = resp.body.getReader();
  const chunks = [];
  let total = 0;

  while (true) {
    // 用 Promise.race 给「等下一块数据」加一个宽限期上限
    const next = reader.read();
    const timer = new Promise((r) => setTimeout(() => r("GRACE"), TERM_GRACE_MS));
    const winner = await Promise.race([next, timer]);

    if (winner === "GRACE") {
      const parsed = tryParse(chunks, total);
      if (parsed) {                 // 数据已完整，是服务端没收尾
        reader.cancel().catch(() => {});
        return parsed;
      }
      continue;                     // 数据还不完整，继续等
    }

    const { done, value } = winner;
    if (done) break;                // 正常路径：服务端收了尾
    chunks.push(value);
    total += value.length;
  }

  const parsed = tryParse(chunks, total);
  if (!parsed) throw new Error("响应不完整");
  return parsed;
}

function tryParse(chunks, total) {
  if (total < MIN_BYTES) return null;                 // 判定 4
  const buf = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { buf.set(c, off); off += c.length; }
  const text = new TextDecoder().decode(buf).trimEnd();
  if (!text.endsWith("}")) return null;               // 判定 6
  try { return JSON.parse(text); } catch { return null; }   // 判定 7
}
```

<Tip>
  两段代码里都留意一下**正常路径**那一行注释：服务端正常收尾时，循环靠 `done` / 迭代结束自然退出，宽限期分支根本不会进。这就是「兼容而不是替换」的具体含义 —— 你原来的成功路径一个字节都没变。
</Tip>

## 超时怎么设

最容易踩的坑是**用一个超时值管两件事**：「等上游把图生成出来」和「首字节之后两块数据之间的静默」。这两段的正常时长差着一个数量级，混成一个值，要么把生成慢误杀成故障，要么让真正卡住的请求白等好几分钟。

| 这一段 | 正常时长 | 建议值 | 为什么 |
| - | - | - | - |
| **等首字节**（上游在生成） | 2K 约 20\~30 秒，4K 更久 | **120\~180 秒** | 这段慢不等于故障，要留够，切短了会误杀正常请求 |
| **首字节之后的块间静默** | 毫秒级 | **3\~5 秒**（宽限期） | 到这个量级还没动静，就该判定并主动收尾 |

<CardGroup cols={2}>
  <Card title="✅ 建议" icon="check">
    两段分开设：首字节留足生成时间，
    块间静默压到几秒，靠上面的主动收尾兜底。
    故障几秒内就能感知，正常请求一个都不误伤。
  </Card>

  <Card title="❌ 不要这样" icon="ban">
    用一个 300 秒的大超时兜一切「以防万一」。
    卡住时服务端一个字节都不会再发，
    等多久都一样，只是白白拖长故障感知时间。
  </Card>
</CardGroup>

<Note>
  如果你的产品里有 4K 这类更耗时的档位：**总超时可以更长**（模型确实要算那么久），但**块间静默的判定不该跟着变长** —— 这是两件事，不要一起放大。
</Note>

<Note>
  Node.js 用户注意：undici（Node 18+ 内置 `fetch` 的底层）有**三个互相独立的超时**，SDK 的 `timeout` 选项管不到它们。配置写法见[连接中断排查](/api-capabilities/image-connection-drops)的「Node.js：三个超时互相独立」一节。
</Note>

## 重试与计费

判定为「服务端没有收尾」之后，按这个顺序处理：

<Steps>
  <Step title="先用已经拿到的图 —— 绝大多数情况到这一步就结束了">
    数据是完整的，图片可以正常使用，**不需要重试**。这既是最省事的路径，也避免了重复计费。
  </Step>

  <Step title="解析确实失败了，才重试">
    如果已收字节真的解析不出完整 JSON（数据确实不完整），再重试。建议换一条新连接，并在重试之间留 2\~3 秒间隔。
  </Step>

  <Step title="连续失败就退避，别贴着重试">
    该问题成时间窗发作，短时间内连续重试很可能仍然落在同一个窗口内。若连续 3 次都卡住，建议退避到 30 秒后再试。
  </Step>
</Steps>

<Warning>
  **计费口径**：这类请求上游已经把图生成出来、也开始正常回传了，属于**交付已完成**，会**照常计费**。所以「客户端超时报错」不等于「没花钱」—— 这正是第一步「直接用已拿到的图」价值最大的地方：图已经付过费了，白白丢掉才是真的浪费。

  完整的断连计费对照（哪些收费、哪些不收费）见[连接中断排查](/api-capabilities/image-connection-drops)的「计费影响」一节。
</Warning>

## 工程落地建议

下面这些与语言、框架无关，是我们自己落地时总结的：

* **落在网络层的统一入口，不要散在业务调用点。** 把它做成「发请求」这个动作的一部分。这样所有出图路径一次性覆盖，业务代码完全无感知，将来服务端修好了也只需要动一个地方。

* **你真正需要的是「增量读取」能力。** 关键前提是能在响应还没结束时就看到已收到的内容。绝大多数 HTTP 客户端都提供这个能力（流式读取、分块回调、进度事件），但**默认用法通常不是** —— 默认那个「直接拿完整响应体」恰恰就是会卡死的那条路。**这是改造的主要工作量所在。**

* **计时以「最后一次收到数据」为准，不是请求开始时间。** 每收到一块数据就重置宽限期计时。这样既不会误伤慢速网络，也能准确捕捉「彻底不动了」的状态。

* **加一个开关。** 把这层行为放在一个可以随时关闭的开关后面。上线初期出现任何非预期情况，关掉即可回到原有行为，不用紧急发版。

* **加埋点。** 每次触发收尾都记一条（时间、数据量、等待时长）。它有三个用途：量化故障实际发生频率、验证这层逻辑确实在起作用、以及在服务端修复之后确认埋点归零 —— 这是判断「可以下线这层逻辑」的唯一客观依据。

* **顺带可以改善的体验。** 既然已经拿到了增量读取能力，就可以顺便把「正在接收数据 X.X MB」这类真实进度展示给用户。大响应体下载期间的等待，原本对用户是完全黑盒的。

## 我们自己的落地情况

这套改造我们已经在自家的 AI 图片大师（`imagen.apiyi.com`）上完成并验证。用一个会复现该故障的模拟服务（完整发完数据后既不发结束信号、也不关连接）跑了一组对照：

| 场景 | 结果 |
| - | - |
| 正常响应（有结束信号） | 走原有路径正常返回，**不触发收尾** —— 无误判 |
| 数据到齐但无结束信号 | 宽限期后主动收尾，数据完整可用 |
| 同上，但开关关闭 | 维持旧行为（一直等） —— 开关有效，可随时回退 |
| 数据只收到一半就挂起 | **不会**误收尾，继续等待 |
| 完整数据但响应头带 `Content-Length` | 被护栏拦住，不收尾 |

结论：**正常请求零影响，故障请求从「等到超时然后失败」变成「几秒内正常出图」。**

## 常见疑问

<AccordionGroup>
  <Accordion title="这会不会把本来正常的请求提前掐断？">
    不会。收尾的前提是已收数据能解析成一份**完整的 JSON** —— 数据没收全时解析必然失败。再加上 3\~5 秒的宽限期，正常请求不会被误判。上面「我们自己的落地情况」那张表里，前两行就是这两种情况的对照。
  </Accordion>

  <Accordion title="会不会拿到半张图？">
    不会。判定的是**整个响应体**的完整性，不是图片本身。JSON 解析通过就意味着图片数据是完整的 —— 半张图对应的是解析失败，那种情况不会触发收尾。
  </Accordion>

  <Accordion title="这算不算掩盖服务端问题？">
    不算。它不替代服务端修复，只是把**已经产生、并且已经计费**的结果交付到用户手里，同时避免了盲目重试带来的重复扣费。埋点数据反过来还能帮助定位故障的发作规律。
  </Accordion>

  <Accordion title="服务端修好之后要不要拆掉？">
    不需要急着拆。有结束信号时这段逻辑永远不会被触发，零开销。可以等埋点连续归零一段时间之后再考虑清理。
  </Accordion>
</AccordionGroup>

## 什么时候找客服

加了上面的兼容之后，如果仍然满足下面任一条，带材料找客服核查：

* 已收字节**始终解析不出完整 JSON**（说明不是本页场景，是真的传输中断）；
* 加了主动收尾之后**仍然长时间拿不到任何响应头**（那是上游还没开始回传，属于生成慢或上游故障，不是收尾问题）；
* 卡住的比例**持续偏高**，不是集中在某个时间窗内，而是长时间稳定复现。

提工单时附上：`x-request-id`、调用时间（**带时区**，如 `2026-08-03 13:15 (UTC+8)`）、模型名与 `imageSize` 等关键参数、客户端异常原文，以及卡住时已收到的字节数。

## 相关文档

<CardGroup cols={3}>
  <Card title="连接中断排查" icon="unplug" href="/api-capabilities/image-connection-drops">
    `ECONNRESET`、SSL EOF、undici 三个超时与本地代理判别矩阵
  </Card>

  <Card title="必读&最佳实践" icon="book-check" href="/api-capabilities/image-api-best-practices">
    同步调用、timeout 分档配置、base64 处理、断连计费口径
  </Card>

  <Card title="自实现异步队列" icon="list-checks" href="/api-capabilities/image-async-queue">
    把同步调用包进任务队列，用重试与落库消化偶发异常
  </Card>
</CardGroup>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.