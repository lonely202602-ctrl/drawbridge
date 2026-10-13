> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 图片 API 连接中断排查

> connection reset by peer、write_response_body_failed、SSL EOF、200 后 body 传一半断开这类报错的排查方法。平台返回 500 write_response_body_failed 不计费；含 macOS / Linux 服务器分别要查什么、线路丢包自查、Node.js undici 三个超时、以及本地代理判别矩阵。

<Warning>
  ### 先说计费：`write_response_body_failed` 这类 500 **不收费**

  网关返回 `500` + `write_response_body_failed` / `connection reset by peer` 时，平台侧**已经自动内部重试了 2-3 次**，全部失败才把错误抛给你。**这种情况不产生任何费用。**

  所以哪怕你在日志里看到一连串这样的报错，**账单上不会有对应扣费**——不用担心「失败了还被收钱」。会计费的是另一种情况（客户端自己提前断开），详见下面的「计费影响」一节。
</Warning>

<Info>
  **一句话结论**：图片 API 的响应体动辄十几到几十 MB，断点几乎总在**下载响应数据**这一程（**不是**请求体太大——纯文生图同样会中断）。排查时先看后台日志属于哪一类，再按 macOS / Linux / Node.js 分别自查。
</Info>

## 报错长什么样

同一个根因，在网关侧和客户端侧会呈现成两副完全不同的面孔。

### 网关侧返回

```json theme={null}
{
  "status_code": 500,
  "error": {
    "message": "write tcp 10.0.0.1:443->203.0.113.5:52310: write: connection reset by peer",
    "type": "shell_api_error",
    "code": "write_response_body_failed"
  }
}
```

### 客户端侧抛出

| 语言 / 库 | 典型异常 |
| - | - |
| Python `requests` / `urllib3` | `SSLError(SSLEOFError(8, 'EOF occurred in violation of protocol'))`、`ChunkedEncodingError`、`ConnectionResetError`、`IncompleteRead` |
| Java（Spring WebClient / Reactor Netty） | `PrematureCloseException: Connection prematurely closed DURING response` |
| Python `httpx` | `RemoteProtocolError`、`ReadError` |
| Node.js（undici / 内置 fetch） | `UND_ERR_CONNECT_TIMEOUT`、`UND_ERR_HEADERS_TIMEOUT`、`UND_ERR_BODY_TIMEOUT`、`SocketError: other side closed` |
| Node.js（其它栈） | `read ECONNRESET`、`ERR_STREAM_PREMATURE_CLOSE`、`socket hang up` |
| Go | `unexpected EOF`、`http2: server sent GOAWAY` |
| curl | `curl: (56) Recv failure`、`curl: (18) transfer closed with outstanding read data remaining` |

<Tip>
  **Node.js 的报错要分清「掐」和「关」**：`ECONNRESET` 表示收到了 TCP RST，是连接被中间设备**掐断**，指向网络路径上的某一跳；`SocketError: other side closed` / `ERR_STREAM_PREMATURE_CLOSE` 表示对端**优雅关闭**（FIN），指向服务端收尾问题（如少发了 chunked 终止块）。两者方向完全不同，不要混为一谈。

  另外 `UND_ERR_*` 只可能来自 undici（Node 18+ 的内置 `fetch` 底层），`read ECONNRESET` 则是 libuv 的顶层文案，`axios` / `node-fetch` / `http` 模块都会给。**如果两类错误同时出现，先确认你的应用里是不是有两条不同的 HTTP 路径**——那样的话它们根本不是同一件事。
</Tip>

## 先判断方向：是谁先断的

`write_response_body_failed` 这个 code 是关键线索——它的含义是**网关在向调用方回写响应体的过程中失败**，属于**下行方向**，不是上游模型报错。换句话说，结果已经生成出来了，正往你这边推的时候连接断了。

<Info>
  **这不是「请求体太大」造成的。** 图片编辑要上传参考图，容易让人误以为是上传体积的问题；但**纯文生图（请求体只有几百字节）同样会中断**。断点在**下载响应数据**的这一程——图片响应动辄十几到几十 MB，是全链路上最脆弱的一段。
</Info>

<CardGroup cols={2}>
  <Card title="下行断开" icon="arrow-down-from-line">
    `write_response_body_failed`、`connection reset by peer`、客户端 SSL EOF。
    网关在推 body 时连接消失。**平台返回 500 的这一类不计费**，详见下面的计费一节。
  </Card>

  <Card title="上游失败（渠道侧）" icon="arrow-up-from-line">
    上游超时、`upstream_error`、5xx 带上游原始报文，或 HTTP 200 但 `finishReason` 异常。
    这类才是渠道问题，可以拿 `x-request-id` 找客服核查。
  </Card>
</CardGroup>

### 最强判据：后台日志里这次是什么状态

在动手排查之前先看后台调用日志。这条零成本，而且比任何客户端操作都更快缩小范围：

| 日志表现 | 含义 | 是否计费 | 下一步 |
| - | - | - | - |
| **正常的计费记录** | 请求到达、上游跑完、网关认为已交付完成 | 计费 | 走下面的四步自证，重点查应用层误读与客户端提前断开 |
| **500 `write_response_body_failed`** | 网关向你回写响应体失败，且平台已**内部重试 2-3 次**仍未成功 | **不计费** | 属于下行链路问题，带 request-id 找客服 |
| **完全没有记录** | 请求**压根没发出去** | 不计费 | 建连阶段，跳到下面的「Node.js 三个超时」与「本地代理 / VPN」两节 |

<Warning>
  由此可以推出一条经常被搞反的结论：**`UND_ERR_CONNECT_TIMEOUT` 这类建连阶段的失败不可能产生计费**，因为请求根本没到网关。所以如果你看到「大量 connect timeout」同时又「被扣了很多次费」，**这两件事一定不是同一批请求**，必须分开排查，不要用一个根因去解释全部现象。
</Warning>

### 四步自证

按这个顺序做，绝大多数情况在前两步就能定位：

<Steps>
  <Step title="先排除应用层误读：你可能收到了但没存住">
    「没收到图」往往是**代码抛异常后被 catch 成「请求失败」**的结论，而不是真的没收到字节。最常见的一种：`gpt-image-2-all` 默认返回 `b64_json` 且**不带 `data:` 前缀**，代码若按 `data[0].url` 取值会拿到 `undefined`，后续处理直接抛错 → 被判定为失败 → 触发重试 → **重复计费**。

    现象与网络故障一模一样，但根本不需要任何网络故障。先打印一行自查：

    ```javascript theme={null}
    console.log(Object.keys(resp.data[0]), resp.data[0].b64_json?.length);
    ```

    各系列的字段与前缀差异见 [base64 前缀差异对照](/api-capabilities/image-api-best-practices#前缀差异对照)。
  </Step>

  <Step title="看是不是「所有渠道 / 所有模型一起报」">
    同一时间窗内，如果你在测的**两个不同渠道、不同模型都在报同一个错**，那几乎可以直接排除渠道特异性——上游不会这么整齐地同时出问题。
  </Step>

  <Step title="查客户端的运行时：TLS 栈（Python）或 undici 超时（Node.js）">
    Python 看 TLS 栈版本，Node.js 看 undici 的三个超时——见下面两节。这是实测中最高频的根因，且完全在你本地，一条命令就能确认。
  </Step>

  <Step title="降并发 / 改串行 / 关掉本地代理再跑一遍">
    把并发降到 1-2、并关掉 VPN 或代理重跑同样的请求。如果这样完全不复现，问题在客户端的连接管理、本地资源（连接池、文件描述符、内存）或网络路径，而不是渠道。
  </Step>
</Steps>

## 头号元凶：客户端 TLS 栈（macOS 尤其高发）

**macOS 系统自带的 Python（`/usr/bin/python3`）链接的是 LibreSSL 2.8.3**，而不是 OpenSSL。这个组合在配合 urllib3 v2 做**并发大响应体下载**时会稳定抛出 `SSLEOFError`，表现为客户端单方面断连——于是网关侧记录下一片 `connection reset by peer`。

### 一条命令自查

```bash theme={null}
python3 -c "import ssl; print(ssl.OPENSSL_VERSION)"
```

| 输出 | 判断 |
| - | - |
| `LibreSSL 2.8.3` | ⚠️ **高危**，并发大响应体场景下会假报连接错误 |
| `OpenSSL 1.1.1x` 及以上 | ✅ 正常 |

导入 `requests` 时如果看到这行告警，同样是中招信号：

```
NotOpenSSLWarning: urllib3 v2 only supports OpenSSL 1.1.1+,
currently the 'ssl' module is compiled with 'LibreSSL 2.8.3'
```

### 修复：换一个解释器

不要去降级 urllib3，直接换用带正常 OpenSSL 的 Python：

```bash theme={null}
# macOS：用 Homebrew 的 Python 建虚拟环境
brew install python@3.13
python3.13 -m venv venv
venv/bin/pip install requests pillow
venv/bin/python -c "import ssl; print(ssl.OPENSSL_VERSION)"   # 应输出 OpenSSL 3.x
```

### 实测对照（2026-07-29，UTC+8）

在 Nano Banana 系列（`gemini-3-pro-image` / `gemini-3.1-flash-image`）上做双渠道对比测试时的真实数据：

| 解释器 | 场景 | 传输层异常率 |
| - | - | - |
| 系统 python3.9（LibreSSL 2.8.3） | 并发 12 跑图 | **大面积报错，两个渠道同时出现** |
| Homebrew python3.13（OpenSSL 3.6.1） | 同样的 108 次调用 | 3 次（2.8%），均为 4K 大响应体，**重试一次即成功** |
| Homebrew python3.13（OpenSSL 3.6.1） | 80 次专项复现（含并发 24 小响应体、并发 12 的 4K、以及串行 4K） | **0 次** |

结论很清楚：**换解释器前后差了一个数量级**，且换之前两个渠道同时报错这一点本身就说明与渠道无关。

## Linux 服务器要查什么（和 macOS 完全不是一回事）

<Info>
  上一节的 TLS 栈自查在 Linux 上**基本都会通过**——各发行版自带的 Python 链的都是正常 OpenSSL，不存在 LibreSSL 那个坑。**所以别在这里停下**：服务器环境的坑在**出网路径**和**容器限制**上，和本机开发完全是两套问题。
</Info>

### 1. 云 NAT 网关 / 负载均衡的空闲超时（服务器上最高频）

这是生产环境 `connection reset by peer` 的头号来源。以 **AWS NAT Gateway** 为例：它有一个**固定 350 秒**、不可调的空闲超时，而且超时后**发的是 RST 不是 FIN**——于是客户端拿到的正好就是 `ECONNRESET`。

坑在于它会**连锁**：连接池里的连接闲置超过 350 秒后集体失效，你发请求时第一条被 RST，客户端自动重试换池里下一条——**那条也闲置超时了，照样 RST**。表现就是「一段时间没调用，然后突然连续几发全挂，之后又恢复正常」。

<Tip>
  这和前面 Node.js 一节讲的「keep-alive 复用死连接」是同一个机制，只不过在服务器上元凶通常是**云厂商的 NAT 网关**，而不是本地代理软件。
</Tip>

修法（任选，推荐前两个）：

* **把 TCP keepalive 调到小于 350 秒**，让静默期也有包在走；
* **限制连接池的空闲存活时间**，让它主动丢弃可能已失效的连接（Node：`new Agent({ keepAliveTimeout: 60_000 })`；Python `requests` 用 `HTTPAdapter` 控制连接池）；
* 走 VPC 端点等绕开 NAT 网关的路径。

其它云厂商与自建 LB 的空闲超时值各不相同，但**思路一样：找出链路上最短的那个空闲超时，把 keepalive 调得比它更小**。

### 2. TCP keepalive 默认值等于没开

Linux 的 `tcp_keepalive_time` 默认是 **7200 秒（2 小时）**，远大于上面任何一个空闲超时，等于完全不起作用：

```bash theme={null}
# 查当前值
sysctl net.ipv4.tcp_keepalive_time net.ipv4.tcp_keepalive_intvl net.ipv4.tcp_keepalive_probes

# 临时调整（容器里需要 --sysctl 或特权，生产建议写进 sysctl.d 或应用层设置 SO_KEEPALIVE）
sudo sysctl -w net.ipv4.tcp_keepalive_time=60
sudo sysctl -w net.ipv4.tcp_keepalive_intvl=15
```

更稳妥的做法是**在应用层的 HTTP 客户端上开 keepalive**，不依赖全局 sysctl——容器里改内核参数往往受限。

### 3. 容器网络的 MTU

Docker / K8s 的 overlay 网络（flannel VXLAN 等）MTU 常被设成 **1450** 而不是 1500，一旦和路径上的 PMTUD 黑洞叠加，就是典型的「小请求全正常、大响应必挂」：

```bash theme={null}
ip link show            # 看容器网卡 MTU
# 用逐步增大的包长探测实际可通过的 MTU（不分片）
ping -M do -s 1400 api.apiyi.com
```

### 4. 容器内存上限 → 进程被 OOMKilled

4K 出图的 base64 单条可达 20-30MB，`resp.json()` 一次性载入再叠加并发，很容易超过容器的 memory limit 被内核杀掉，**表现同样是「连接莫名其妙断了」**：

```bash theme={null}
# 容器是不是被 OOM 杀的
dmesg -T | grep -i -E "oom|killed process"
kubectl describe pod <pod> | grep -A3 "Last State"   # 看是否 OOMKilled
```

对策见下面的「流式读取，别一次性载入」。

### 5. 环境变量里的代理（服务器上最隐蔽的一个）

服务器上经常有全局 `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY`（写在 `/etc/environment`、systemd unit 或 Dockerfile 里），你自己都忘了它的存在。**更麻烦的是各语言对它的处理并不一致**：

| 客户端 | 是否自动读取 `HTTPS_PROXY` |
| - | - |
| Python `requests` / `httpx` | ✅ 默认读取 |
| curl | ✅ 默认读取 |
| **Node.js 内置 `fetch`（undici）** | ❌ **默认不读**，要显式用 `ProxyAgent` / `EnvHttpProxyAgent` |

这个不一致会造成非常迷惑的现象：**同一台机器上 curl 和 Python 走代理、Node 直连**（或反过来），两者行为不同，排查时容易得出矛盾结论。先确认一下：

```bash theme={null}
env | grep -i -E "proxy|no_proxy"
```

API易 国内可直连，**服务器上通常应该把 `api.apiyi.com` 加进 `NO_PROXY`**，或干脆确认没有代理变量。

### 服务器侧一键自查

```bash theme={null}
echo "--- 代理变量 ---";   env | grep -i proxy || echo "无"
echo "--- keepalive ---";  sysctl net.ipv4.tcp_keepalive_time
echo "--- MTU ---";        ip link show | grep mtu
echo "--- fd 上限 ---";     ulimit -n
echo "--- DNS 解析 ---";    getent hosts api.apiyi.com
echo "--- 连通性与耗时 ---"
curl -sS -o /dev/null -w 'connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} ip=%{remote_ip}\n' \
  https://api.apiyi.com/v1/models -H "Authorization: Bearer $KEY"
```

## 线路丢包：响应头到了，body 传一半就断

服务器直连 API易 时，如果这台机器（或它所在机房）的出口线路质量差，会出现一种很好认的断法：**HTTP 200 的响应头已经收到，body 下载到一小部分后连接就被关闭了**。图片其实已经生成成功，是在把结果传回你服务器的这段公网上断的。

### 典型报错

| 客户端 | 报错 |
| - | - |
| curl | `curl: (18) transfer closed with 3129248 bytes remaining to read`，同时 `-w` 打出的状态码是 `200` |
| Java Spring WebClient（Reactor Netty） | `WebClientResponseException: 200 OK from POST https://api.apiyi.com/v1/images/edits, but response failed with cause: PrematureCloseException: Connection prematurely closed DURING response` |
| Python | `IncompleteRead(134335 bytes read, 3129248 more expected)`（`requests` 会把它包在 `ChunkedEncodingError` 里抛出） |

### 怎么认出它：三个特征

1. **状态码是 200，而且报出了「还差多少字节」**。报得出剩余字节数，说明响应带了 `Content-Length`。也就是说，网关是拿到了完整结果、知道总大小之后才开始发送的。**生成已经成功，断点只可能在下行传输上。**
2. **总耗时远大于生成耗时**。curl 的 `time_starttransfer`（收到首字节的时间）大致等于生成耗时。如果 `time_total` 比它多出几分钟，而 `size_download` 只有几十到几百 KB，说明数据传不动。
3. **按时段发作，换一台机器就正常**。同一台服务器在白天某几个小时集中失败，其它时段正常；同一时间用另一台机器（或你的本地电脑）跑同样的请求，却完全正常。

<Info>
  为什么最后是「连接被关闭」，而不是一直卡着：线路严重丢包时，数据包和确认包都在大量丢失，服务端**长时间收不到客户端的接收确认，就会关闭连接**。所以它看起来像超时，根因却是丢包。**把你这边的超时调大解决不了问题。**
</Info>

### 自查步骤

<Steps>
  <Step title="用 curl 复现，记下时间剖面">
    ```bash theme={null}
    curl -sS -X POST https://api.apiyi.com/v1/images/edits \
      -H "Authorization: Bearer $APIYI_API_KEY" \
      -F model=gpt-image-2 -F prompt=换个背景 -F n=1 \
      -F 'image=@ref.jpg;type=image/jpeg' \
      -D headers.txt -o resp.json \
      -w 'http=%{http_code} ttfb=%{time_starttransfer} total=%{time_total} size=%{size_download} speed=%{speed_download}\n'
    grep -i -E "content-length|x-shellapi-request-id" headers.txt
    ```

    判读：`http=200`、`size` 明显小于 `Content-Length`、`total` 远大于 `ttfb`，就是下行传输出了问题，继续做下一步。
  </Step>

  <Step title="测丢包：ping 和 mtr">
    ```bash theme={null}
    ping -c 100 api.apiyi.com
    mtr -rwc 100 api.apiyi.com      # 没装的话：yum install mtr 或 apt install mtr
    ```

    正常线路的丢包应该接近 0%。只看**一直延续到最后一跳**的丢包：中间某一跳单独显示丢包、后面几跳又恢复正常的，通常只是那台路由器不优先回应 ping，不算数。**最好在失败高发的时段跑**，线路好的时候测不出问题。
  </Step>

  <Step title="换一台机器对照">
    同一时间，在另一台服务器（最好是不同机房或不同云厂商）或你的本地电脑上跑第 1 步的同一条命令。对照机正常、只有这台失败，问题就在这台机器的网络出口，不在接口本身。
  </Step>

  <Step title="看有没有时段规律">
    把每次调用的时间、`http_code`、`size`、`total` 记下来，看失败是不是集中在某几个时段。按时段发作是线路拥塞的典型特征。
  </Step>
</Steps>

<Tip>
  **一个真实案例（2026-09-27）**：出图 48 秒就完成了，完整的响应也已经送到出口节点，但客户服务器所在机房的线路当时丢包 17%\~37%（同一时刻，同一批节点到其它国内服务器的丢包是 0%），客户最终只收到约 4% 的数据。同一时段、同一批节点上的其他客户全部正常收完。
</Tip>

### 缓解办法

1. **把响应体变小（立刻见效）**：gpt-image 系列加上 `output_format=jpeg` 和 `output_compression=85`。2026-09-27 实测：n=1 的响应从约 2.4 MB 降到约 0.5 MB，n=5 从约 12 MB 降到约 2.6 MB，**小了约 4.7 倍**。要传的数据越少，在丢包线路上越容易传完。支持 `response_format: "url"` 的模型（如 `gpt-image-2-vip`、`gpt-image-2-all`）可以直接改为返回链接，响应体只剩几百字节。
2. **把 n>1 拆成多个并发的 n=1**：n=5 的响应是 n=1 的 5 倍大，更容易断，而且一断就整批作废。拆开后每个响应都小，失败只影响一张。
3. **换出口（根治）**：把调用 API易 的服务放到线路质量好的机器上（比如主流云厂商的 BGP 或精品线路机型），或者经这类机器中转。也可以把 mtr 结果交给你的机房或运营商排查。
4. **客户端配置**：修不了丢包，但能避免线路恢复后出现别的问题。以 Java WebClient 为例，`maxInMemorySize` 默认只有 256 KB，而 n>1 的响应动辄十几 MB，需要调到 32 MB 以上，否则会报 `DataBufferLimitException`；`responseTimeout` 至少设 300 秒：

   ```java theme={null}
   HttpClient httpClient = HttpClient.create()
       .responseTimeout(Duration.ofSeconds(300));
   WebClient client = WebClient.builder()
       .clientConnector(new ReactorClientHttpConnector(httpClient))
       .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
       .build();
   ```

<Warning>
  **重试前先想清楚：这类失败照常计费。** 连接断开时图片已经生成，平台也已完成交付，后台日志里是一条正常的计费记录。所以每重试一次，就是再生成一次、再付一次钱。线路没有改善之前，盲目重试只会让费用成倍增加，先做上面第 1\~3 条。
</Warning>

需要找客服核查时，请提供：

* 失败时间，精确到分钟并带时区，如 `2026-09-27 13:32 (UTC+8)`
* 服务器的出口 IP（可以用 `curl -s ipinfo.io/ip` 查）
* 响应头里的 `X-Shellapi-Request-Id`（第 1 步命令会打印出来）
* 第 1 步 curl `-w` 输出的那一行
* `mtr` 的结果

想进一步测出你的服务器到我们接口的实际带宽，见[日志显示已完成但客户端收不到响应](/faq/log-duration-vs-client-wait)。

## Node.js：三个超时互相独立，SDK 的 timeout 管不到

Node 18+ 的内置 `fetch` 底层是 undici，它有**三个各自独立的超时**，分别对应请求的三个阶段。「我 timeout 设了 5 分钟」通常只调到了其中一个都不是的第四个值：

| 错误码 | 发生阶段 | undici 默认值 | 由什么控制 | 会被计费吗 |
| - | - | - | - | - |
| `UND_ERR_CONNECT_TIMEOUT` | 建连（TCP + TLS 握手） | **10 秒** | `connect.timeout` | **不会**（请求没到网关） |
| `UND_ERR_HEADERS_TIMEOUT` | 等首个响应头 | 300 秒 | `headersTimeout` | 会 |
| `UND_ERR_BODY_TIMEOUT` | **相邻两个 body chunk 之间**的间隔 | 300 秒 | `bodyTimeout` | 会 |

<Warning>
  **openai-node 的 `timeout` 选项是基于 AbortController 的「总请求超时」，不会传导到上面三个中的任何一个。** 你把 `timeout` 从 60 秒调到 300 秒，`connectTimeout` 依然是 10 秒。裸 `fetch()` 的 `AbortSignal.timeout()` 同理。

  这就是「明明 timeout 设得很大却还是报超时」最常见的原因——调错了层。
</Warning>

### 正确的配置写法

要放宽 undici 的三个超时，必须配 `Agent`（全局或按请求）：

```javascript theme={null}
import { Agent, setGlobalDispatcher } from "undici";
import OpenAI from "openai";

// 图片接口是「长时间静默 + MB 级响应体」，三个超时都要单独放宽
setGlobalDispatcher(new Agent({
  connect: { timeout: 30_000 },   // 建连 30s，默认只有 10s
  headersTimeout: 300_000,        // 等首包 300s
  bodyTimeout: 300_000,           // chunk 间隔 300s
}));

const client = new OpenAI({
  apiKey: process.env.APIYI_API_KEY,
  baseURL: "https://api.apiyi.com/v1",
  timeout: 300_000,   // 总超时，与上面三个是不同层，都要设
  maxRetries: 0,      // 关键：见下
});
```

### `maxRetries` 默认是 2，而且会自动重试连接错误

openai-node **默认 `maxRetries: 2`，并且连接错误和超时都在自动重试范围内**。也就是说一次业务调用最多会产生 **3 次实际请求**（其中会不会计费，取决于每一次分别属于下面「计费影响」的哪一类），而你的代码里可能一次重试都没写。

图片接口单价高、又是同步长请求，**一律显式设 `maxRetries: 0`，把重试收到自己手里**，配合自己的退避与次数上限。计费口径见 [重试策略](/api-capabilities/image-api-best-practices#重试策略)。

<Tip>
  排查时先确认你到底用的是什么栈：`node -v`、`npm ls openai undici axios node-fetch`。`UND_ERR_*` 只证明底层走的是 undici，**不能证明你用的是 openai SDK**——裸 `fetch()` 同样会抛这些错误码，而裸 `fetch()` 没有 `maxRetries` 这回事。
</Tip>

### keep-alive 复用了一条已经死掉的连接

undici 默认启用连接池 + keep-alive。VPN、NAT、代理软件把空闲连接静默回收之后，客户端并不知情，仍然从池里取出这条连接来发下一个请求——**写入的瞬间收到 RST，表现就是 `read ECONNRESET`**。

这是 `ECONNRESET` 在长间隔调用场景下最常见的来源，也能解释「错误在某个时间窗内密集出现」「重试的第一发也失败」。验证方法是关掉复用再跑：

```javascript theme={null}
const agent = new Agent({ pipelining: 0, keepAliveTimeout: 1_000 });
// 错误随之消失 ⇒ 就是死连接复用
```

## 本地代理 / VPN：图片接口最容易暴露的一跳

<Info>
  **API易 国内可直连，不需要代理或 VPN**（见 [使用 API 接口需要代理网络吗？](/faq/network-proxy)）。所以排查时，**关掉代理直连复测是成本最低、信息量最大的一发**。

  但要说清楚：代理只是**嫌疑最大的变量之一**，不等于根因。下面的判别矩阵才是用来定位的。
</Info>

图片接口有两个特征让它比文本接口敏感得多：**生成期有 30-60 秒零字节流动**，以及**响应体是 MB 级的一次性突发**。普通聊天接口跑得好好的，图片接口挂掉，往往就卡在这两点上。

<CardGroup cols={2}>
  <Card title="fake-ip / 分流规则不命中" icon="route-off">
    代理软件的 fake-ip 模式下，若规则没命中，会连到 `198.18.x.x` 这类不可路由地址，表现是**精确 10 秒**的 connect timeout。注意这不是「建连慢」，是**根本没有路由**——放大 `connect.timeout` 也救不回来。务必记录实际连到的 `remote_ip`。
  </Card>

  <Card title="生成期被当成空闲连接回收" icon="timer-off">
    请求发出后有 30-60 秒零字节流动，代理按空闲连接策略回收。特征是**失败时刻是 30 / 60 / 120 这类圆整值**，且与图片大小无关。
  </Card>

  <Card title="MTU / PMTUD 黑洞" icon="package-x">
    隧道 MTU 小于路径 MTU，而 ICMP「需要分片」被丢弃导致 PMTUD 失效。典型表现是**小请求全正常、大响应必挂**，已收字节停在几 KB 到几十 KB 就不动了。把隧道 MTU 降到 1400 左右常能解决。
  </Card>

  <Card title="MITM 解密 + 全量缓冲" icon="shield-off">
    开了 HTTPS 解密的代理常对大 body 做整体缓冲，可能撞上体积上限；也可能把 chunked 重写成 `Content-Length` 而长度算错，直接 RST。同样只打图片这种 MB 级响应，不打文本调用。
  </Card>
</CardGroup>

### 判别矩阵

这是本节的核心。判别轴只有两条：**失败发生在首字节之前还是之后**、**已经收到了多少字节**。

| 观测项 | 建连超时 | 代理空闲回收 | MTU 黑洞 | 服务端缺 chunked 终止块 |
| - | - | - | - | - |
| TTFB（首字节） | 永远没有 | 永远没有 | 有 | **正常**（与生成耗时一致） |
| 已收字节数 | 0 | 0 | **0 \< N ≪ 全量** | **= 全量，JSON 可完整解析** |
| 失败时刻 | **≈10.0 秒，极稳定** | 圆整值，与图大小无关 | 不定 | last-byte 之后 **+300 秒被断开**；也可能**无限期挂住**（实测等 330 秒仍无变化） |
| 连接终态 | ConnectTimeout | RST | 挂死或 RST | FIN（优雅关闭）；也可能**既无终止块也无 FIN，连接一直开着** |
| 是否计费 | **否**（没到网关） | 见「计费影响」一节 | 见「计费影响」一节 | 见「计费影响」一节 |
| 换 `response_format: "url"` | 仍失败 | 仍失败 | **变正常** | **变正常** |

最后一行是性价比最高的一发：把响应体从数 MB 压到 1KB 左右，**如果 URL 模式稳定成功而 base64 模式稳定失败，就说明问题与传输体量相关**，可以直接排掉建连和空闲回收两列。

<Info>
  最右边那一列（**服务端缺 chunked 终止块**）近期出现了新的形态：**既不发终止块、也不关连接，无限期挂住**，不再是过去那种「+300 秒后被优雅关闭」。两种形态的共同点是**数据已经完整、图片可以直接用**，处理方式也一样 —— 在客户端主动收尾，详见[请求收尾卡住](/api-capabilities/image-tail-stall)。
</Info>

### 一条命令看清时间剖面

```bash theme={null}
curl -sS -o /tmp/out.json --trace-time \
  -w '\nconnect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} bytes=%{size_download} code=%{http_code} ip=%{remote_ip}\n' \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gpt-image-2-all","prompt":"a red cube on a white table"}' \
  https://api.apiyi.com/v1/images/generations
```

读法对着上面的矩阵：`connect` 没值 → 建连阶段；`ttfb` 没值且 `total` 是圆整数 → 空闲回收；`bytes` 是全量但 `total ≈ ttfb + 300` 并报 `curl: (18)` → 服务端没发终止块；`bytes` 卡在几十 KB → MTU。

<Warning>
  **做代理开 / 关的 A/B 对比时必须交替执行，不能分块。** 先连跑 5 次代理、再连跑 5 次直连，这种顺序分组会被**时间窗性质的故障**污染出完全错误的结论——实测中确实遇到过某段时间全挂、隔几分钟全好、再过一会儿又复发的情况。正确做法是 `代理 → 直连 → 代理 → 直连` 交替，并记录每次的 `remote_ip`。
</Warning>

## 其它常见诱因

<CardGroup cols={2}>
  <Card title="中途手动中断" icon="octagon-x">
    调试时 Ctrl+C、重启进程、热重载、kill 掉正在跑的脚本——所有正在传输的大响应体都会在网关侧留下一条 `write_response_body_failed`。这是最容易被误读成「渠道不稳」的假警报。
  </Card>

  <Card title="外层超时先到" icon="timer-off">
    任务队列 worker 超时、Serverless 函数执行上限、网关/CDN 的回源超时（默认普遍 60 秒）。任何一层小于生成时间都会先掐断连接，详见[必读&最佳实践](/api-capabilities/image-api-best-practices#超时与断连排查)。
  </Card>

  <Card title="连接池与并发过高" icon="waypoints">
    连接池上限、本地文件描述符上限、NAT / 防火墙对长连接的静默回收。大响应体持续时间长，撞上这些限制的概率远高于文本接口。
  </Card>

  <Card title="内存扛不住响应体" icon="memory-stick">
    4K 出图的 base64 单条可达 20-30MB，一次性 `resp.json()` 全量载入再加并发，容器内存打满会导致进程被 OOM 杀掉，表现同样是「连接莫名断开」。
  </Card>
</CardGroup>

## 计费影响：哪些断连收费，哪些不收费

这两种情况经常被混为一谈，但计费结果完全相反：

<Info>
  ### 平台返回 500 `write_response_body_failed` —— **不计费**

  这个错误表示网关在向你回写图片数据时连接断了。**平台侧会自动内部重试 2-3 次**，重试全部失败之后才把 500 抛给你。

  **这种情况不产生任何费用。** 所以即使你在日志里看到一连串这样的报错、而且是同一个请求反复失败，**账单上不会有对应的扣费**，不用担心「失败了还被收钱」。
</Info>

<Warning>
  ### 客户端自己提前走开 —— **照常计费**

  另一种情况是网关正常完成了交付，是**你这边先断的**：客户端 timeout 到点主动断开、调试时 Ctrl+C、进程被重启或被 OOM 杀掉。

  这类请求服务端与上游的生成**已经完成**，**照常计费**——「我没拿到图」不等于「没花钱」。所以排查期间反复重试大图请求，这部分账单是实打实在走的。
</Warning>

区分方法就是上面那张表：**看后台日志记的是正常调用还是 500 `write_response_body_failed`**。

重试策略也要相应克制：传输层异常值得重试，但**每次重试都可能是一次新的计费**（取决于它属于上面哪一类）。不要写无上限的重试循环。

## 正确的重试写法

关键原则：**只对传输层异常重试，不对 HTTP 层错误重试**。4xx 重发一万次也还是 4xx，而且浪费时间。

```python theme={null}
import time
import requests

TRANSPORT_ERRORS = (
    requests.exceptions.SSLError,
    requests.exceptions.ConnectionError,
    requests.exceptions.ChunkedEncodingError,
    requests.exceptions.ReadTimeout,
)

def call_image_api(url, headers, body, timeout=360, retries=2):
    """传输层异常最多重试 retries 次；HTTP 4xx/5xx 一律不重试，直接交给上层判断。

    注意：每次重试都可能是一次新的计费请求，retries 不要设大。
    """
    attempts = []
    for i in range(retries + 1):
        try:
            resp = requests.post(url, headers=headers, json=body,
                                 stream=True, timeout=(10, timeout))
            raw = b"".join(resp.iter_content(chunk_size=8192))
            attempts.append({"attempt": i + 1, "status": resp.status_code})
            return resp.status_code, raw, attempts      # 含 4xx/5xx，交上层处理
        except TRANSPORT_ERRORS as e:
            attempts.append({"attempt": i + 1, "error": repr(e)})
            if i == retries:
                raise
            time.sleep(2 + 3 * i)                       # 2s、5s 退避
```

<Tip>
  **把每次尝试单独记下来**（上面的 `attempts`）。否则客户端重试成功后，日志里只剩一条漂亮的 200，你会永远看不到底层到底断了多少次——排查渠道质量时这份数据是关键，也能避免把自己的重试误读成渠道行为。
</Tip>

### 流式读取，别一次性载入

大响应体建议用 `stream=True` 逐块读取，既能降低内存峰值，也能在出问题时看清**是在传输的哪个阶段断的**：

```python theme={null}
resp = requests.post(url, headers=headers, json=body, stream=True, timeout=(10, 360))
chunks, total = [], 0
for chunk in resp.iter_content(chunk_size=8192):
    total += len(chunk)
    chunks.append(chunk)
raw = b"".join(chunks)
# total 远小于 Content-Length ⇒ 传到一半断了
# total 完整但连接不关 ⇒ 上游缺 chunked 终止块，属于渠道问题
```

后一种情况**不要重试**：数据已经完整，图片可以直接用。客户端主动收尾的完整写法见[请求收尾卡住](/api-capabilities/image-tail-stall)。

## 什么时候才该找客服

自证走完之后，如果满足下面**任一条**，就带着材料找客服核查：

* 换了正常 OpenSSL 的解释器、并发降到串行，**仍然稳定复现**；
* 只有**某一个特定渠道 / 模型**在报，其它渠道同时段正常；
* 响应体**已经完整收到**（字节数对得上 `Content-Length`）但连接迟迟不关闭，直到超时——这是上游缺 chunked 终止块，属于渠道侧问题。**先按[请求收尾卡住](/api-capabilities/image-tail-stall)加一层主动收尾把图取出来**，仍有问题再提工单；
* 报错是明确的上游方向（`upstream_error`、上游 5xx 原文）。

提工单时附上：`x-request-id`、调用时间（**带时区**，如 `2026-07-29 14:32 (UTC+8)`）、模型名、`imageSize` 等关键参数、客户端异常原文、以及你已经做过的自证步骤。

## 相关文档

<CardGroup cols={3}>
  <Card title="请求收尾卡住" icon="hourglass" href="/api-capabilities/image-tail-stall">
    图已传完但连接不结束时，在客户端主动收尾的兼容写法
  </Card>

  <Card title="必读&最佳实践" icon="book-check" href="/api-capabilities/image-api-best-practices">
    同步调用、timeout 分档配置、base64 处理、断连计费口径
  </Card>

  <Card title="自实现异步队列" icon="list-checks" href="/api-capabilities/image-async-queue">
    把同步调用包进任务队列，用重试与落库消化偶发断连
  </Card>

  <Card title="需要代理网络吗？" icon="wifi" href="/faq/network-proxy">
    API易 国内直连、无需代理；证书与 DNS 类问题的自查方法
  </Card>

  <Card title="Gemini 图片错误处理" icon="triangle-alert" href="/api-capabilities/gemini-image-error-handling">
    Gemini 系出图的错误码与 finishReason 处理
  </Card>

  <Card title="错误信息留存" icon="clipboard-list" href="/api-manual/error-reporting">
    怎么把上面这些报错完整打印下来，以及提工单该带哪些字段
  </Card>
</CardGroup>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.