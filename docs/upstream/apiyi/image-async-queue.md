> ## Documentation Index
> Fetch the complete documentation index at: https://docs.apiyi.com/llms.txt
> Use this file to discover all available pages before exploring further.

# 图片 API 接入实践：自实现异步队列

> API易 图片 API 是同步接口。本文分享如何在同步接口之上自行实现异步任务队列：任务式管理、重试解耦、结果落库与多服务商接入。

<Info>
  本页是一篇**技术分享 / 建议篇**，面向需要把图片生成接入到自己产品里的开发团队。我们只提供工程实践思路，不涉及任何需要 API易 侧改造的功能——你完全可以基于现有的同步接口落地。
</Info>

## 同步还是异步？先理解 API易 的接口模型

API易 的图片生成**全部是同步接口**：`/v1/images/generations` 等端点「请求一旦提交就会跑到结束」。客户端即使中途断开连接，服务端仍会把这次出图完整执行完——也就是说，它不是「先拿一个 task\_id、再轮询结果」的异步任务接口。

<Info>
  API易 在网关层已经把上游的异步 polling（部分服务商官方走 `polling_url` 轮询）封装成了**同步的 OpenAI Images API**。对你而言永远是「一次提交、一次拿结果」，**不需要自己写轮询循环**。
</Info>

很多团队第一反应是：「那我怎么做异步任务管理？」——这其实是两件事：

* **同步**：是 API易 接口的形态（HTTP 请求级别，发一次拿一次）。
* **异步队列**：是**你这一侧的工程实践**（业务任务级别，提交即返回、后台慢慢跑）。

两者并不冲突。下面讲的，就是如何在同步接口之上，自己包一层异步队列。

## 为什么开发团队仍然需要「任务式」管理

直接在用户请求线程里同步调一次图片 API，对 Demo 足够；但一旦面向 C 端用户、要做产品，你几乎一定需要把「业务任务」和「单次 HTTP 调用」解耦。原因有四：

<CardGroup cols={2}>
  <Card title="成功 ≠ 一次调用" icon="repeat">
    一个「成功的任务」往往由**多次同步调用**拼成：首次超时、偶发 429/503 都需要重试。把任务和单次调用解耦后，重试、退避、超时都对最终用户透明——用户只看到「这张图最终成了」。
  </Card>

  <Card title="透明转发，不存输入输出" icon="database">
    API易 只做**透明转发，不存储用户的输入和输出**（prompt、参考图、出图结果都不留底）。要给用户做历史记录、状态查询、结果留存，**必须自己落库**——这是产品侧绕不开的一步。
  </Card>

  <Card title="C 端体验更友好" icon="face-slightly-smiling">
    用户提交即拿到 `task_id`，前端**轮询任务状态**而不是干等一条长连接。刷新页面、临时断网都不会丢任务；批量出图也能排队跑、逐个回填。
  </Card>

  <Card title="多服务商接入成为可能" icon="layers">
    一旦有了自己的任务抽象层，Worker 层就能按需切换/容灾/比价**多个服务商**——退一万步讲，这让「不把鸡蛋放一个篮子」成为可能。
  </Card>
</CardGroup>

## 参考架构：把同步调用包进异步队列

核心思路只有一句：**API 层只负责"收任务、入队、返回 task\_id"，真正的同步调用交给后台 Worker。**

```text theme={null}
  C端/前端 ──①提交任务──▶  API 层  ──②入队──▶  队列 (Redis / MQ / DB 表)
      ▲                      │                              │
      │ ⑤轮询 task 状态        │ 立即返回 task_id               │ ③取任务
      │                      ▼                              ▼
      └──────────────────  数据库  ◀──④落库(状态/输入/输出URL)── Worker
                                                              │ 同步调用 API易
                                                              │ (带重试 / 退避)
                                                              ▼
                                                  api.apiyi.com (同步图片 API)
```

<Steps>
  <Step title="提交即返回">
    前端把出图请求发给你自己的 API 层；API 层创建一条任务记录（状态 `pending`）、写入队列，**立即把 `task_id` 返回给前端**。整个过程不阻塞用户，毫秒级返回。
  </Step>

  <Step title="入队">
    队列可以很轻：Redis List / Stream、RabbitMQ / Kafka，甚至一张带 `status` 字段的数据库表配合定时扫描都行。选型取决于你的量级，不必一上来就上重型中间件。
  </Step>

  <Step title="Worker 同步调用 + 重试">
    后台 Worker 从队列取任务，把状态置为 `running`，**同步调用** API易 图片接口。遇到可重试错误时按指数退避重试（见下文「重试与计费」），全程对用户透明。
  </Step>

  <Step title="落库">
    无论成功失败，都把结果写回数据库：成功则存输出图地址、耗时、计费元数据，状态置 `succeeded`；失败则存错误信息、状态置 `failed`。**这一步就是 API易 不替你做、必须自己做的部分。**
  </Step>

  <Step title="前端轮询">
    前端拿着 `task_id` 周期性查任务状态（或用 WebSocket / SSE 推送）。任务完成就展示结果，失败就给出友好提示。用户的浏览器从头到尾不需要挂着一条长连接。
  </Step>
</Steps>

## 任务状态机与数据模型

建议用一个清晰的状态机来描述每个任务的生命周期：

| 状态 | 含义 | 典型流转 |
| - | - | - |
| `pending` | 已入队，等待 Worker 领取 | → `running` |
| `running` | Worker 正在同步调用 API易 | → `succeeded` / `retrying` / `failed` |
| `retrying` | 命中可重试错误，等待退避后重试 | → `running` |
| `succeeded` | 出图成功，结果已落库 | 终态 |
| `failed` | 重试用尽或不可重试错误 | 终态 |

任务表建议至少记录以下字段（具体类型按你的技术栈而定）：

| 字段 | 说明 |
| - | - |
| `task_id` | 任务唯一标识，提交时即返回给前端 |
| `status` | 上表中的状态枚举 |
| `provider` / `model` | 使用的服务商与模型（为多服务商预留） |
| `input` | 用户输入（prompt、参考图引用、尺寸等参数） |
| `output_url` | 出图结果地址（建议转存自有存储后的 URL） |
| `retry_count` | 已重试次数，用于限流与排查 |
| `error` | 失败原因（错误码 + 友好文案） |
| `created_at` / `updated_at` | 创建与最近更新时间（建议带时区，如 UTC+8） |
| `latency` / `cost` | 耗时与计费元数据，便于成本核算与监控 |

<Tip>
  出图结果建议**转存到你自己的对象存储**（OSS / S3 等）后再把 URL 落库，不要长期依赖第三方返回的临时链接——临时链接可能过期，自己存一份对 C 端展示更稳定。
</Tip>

## 重试与计费：哪些该重试，哪些不该

「任务式管理」最大的价值就是把重试做对。不同错误的计费与重试策略并不一样：

| 场景 | 是否计费 | 是否重试 |
| - | - | - |
| `429` / `503`（限流 / 上游繁忙） | 不计费 | ✅ 可重试，指数退避，建议 2 次 |
| 客户端超时主动断开 | **仍计费** | ⚠️ 可重试，但要先按分辨率设置合理超时（约 60–600s） |
| 内容安全拒绝（状态码 200 仍计费） | **仍计费** | ❌ 不应重试，应回传用户友好提示 |

<Warning>
  把「**业务任务的重试次数**」和「**是否产生计费**」分开记账。`429/503` 重试不计费可以放心退避；但超时断开、内容安全拒绝即使「失败」也已计费——盲目重试会**放大成本**。重试前先判断错误类型，再决定要不要再花一次钱。
</Warning>

错误判定与友好提示的完整口径，见下面两篇：

<CardGroup cols={2}>
  <Card title="Gemini 出图错误处理" icon="triangle-alert" href="/api-capabilities/gemini-image-error-handling">
    出图失败的判断指标、内容审核政策与友好提示方案。
  </Card>

  <Card title="出图失败保障计划" icon="shield-check" href="/api-capabilities/nano-banana-pro-guarantee">
    非主观原因导致的失败，按条数核算后补发额度。
  </Card>
</CardGroup>

## 进阶：一套队列接多个服务商

有了任务抽象层，Worker 调用就可以从「写死调某个接口」变成「按 `provider` 路由」。统一一个 `submit(provider, payload)` 入口，Worker 按任务里的 `provider` 字段决定实际打到哪个上游：

* **容灾**：A 服务商连续失败时自动切到 B，对用户无感。
* **比价 / 分流**：按成本或场景把不同任务分给不同服务商或模型。
* **灰度**：新模型先放一小部分流量验证，再逐步放量。

<Info>
  多数情况下其实**不需要**自建多服务商层：API易 本身已聚合 gpt-image-2、Nano Banana、FLUX、Seedream 等多模型，一个 API易 Key 即可在同一接口风格下覆盖大部分需求。自建 provider 抽象层是「退一万步」的可选项——当你确实需要跨服务商容灾或比价时再上。
</Info>

## 常见问题

<AccordionGroup>
  <Accordion title="同步接口为什么不直接给我一个异步 task 接口？">
    图片出图本身就是「发一次、拿一张图」的强同步语义，封装成同步接口对绝大多数调用方最简单——不用维护轮询、不用处理 task 过期。是否需要异步队列、状态机、落库，取决于你的**产品形态**（是否面向 C 端、是否要历史记录），所以这部分留给你按需自建最灵活。
  </Accordion>

  <Accordion title="客户端超时主动断开，任务还在跑吗？会计费吗？">
    会继续跑。同步端点一旦收到请求就会执行到结束，客户端断开**不会**中止服务端出图，且这次出图**照常计费**。所以请按分辨率设置足够的超时（约 60–600s），不要把超时设得过短导致「白花钱还拿不到图」。
  </Accordion>

  <Accordion title="API易 会帮我存历史出图记录吗？">
    不会。API易 只做**透明转发，不存储用户的输入和输出**。要给用户提供历史记录、状态查询、结果留存，需要你在自己这一侧落库——这正是本文建议「任务式管理」的核心原因。
  </Accordion>

  <Accordion title="我已经在用 API易 的一个 Key，还需要多服务商层吗？">
    多数情况不需要。API易 已经在一个接口风格下聚合了多家模型，一个 Key 通常够用。只有当你有跨服务商容灾、比价、合规分流等明确诉求时，再考虑在 Worker 层加 provider 抽象——这是可选项，不是必需项。
  </Accordion>
</AccordionGroup>

## 相关文档

<CardGroup cols={2}>
  <Card title="图片 API 调用须知与最佳实践" icon="book-check" href="/api-capabilities/image-api-best-practices">
    各模型 timeout 速查表、base64 处理与 URL 输出对照。
  </Card>

  <Card title="为什么没有异步接口" icon="circle-question-mark" href="/faq/image-async-api">
    FAQ：图片生成有异步接口吗？支持任务 ID 查询吗？
  </Card>

  <Card title="FLUX 概览" icon="sparkles" href="/api-capabilities/flux/overview">
    上游异步 polling 被封装成同步 OpenAI Images API 的实例。
  </Card>

  <Card title="Nano Banana 开发指南" icon="compass" href="/api-capabilities/nano-banana-dev-guide">
    同步多线程调用、超时设置与计费基础一站式说明。
  </Card>

  <Card title="Gemini 出图错误处理" icon="triangle-alert" href="/api-capabilities/gemini-image-error-handling">
    出图失败的判断指标与友好提示方案。
  </Card>

  <Card title="出图失败保障计划" icon="shield-check" href="/api-capabilities/nano-banana-pro-guarantee">
    非主观原因失败的额度补发规则。
  </Card>
</CardGroup>


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.