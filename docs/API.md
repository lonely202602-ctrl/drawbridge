# drawbridge 对外 API 文档（业务方版）

Base URL：`http://<网关地址>:8180`

统一响应壳：

```json
{ "code": 0, "msg": "ok", "data": { ... } }
```

- `code = 0` 成功；非 0 见文末错误码表。
- 鉴权：所有接口携带 `Authorization: Bearer sk-xxxx`（SK 在 drawbridge 平台创建，只显示一次）。
- 结果 URL 为上游原样返回，**有有效期**，请及时取用并转存到你方存储（网关在任务存档期内保留结果存档，但上游链接过期不补）。
- 纯网关模式：**不计费、不扣额度**，失败任务也不会产生任何费用。

---

## 1. 提交生图任务

```
POST /v1/images/tasks
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| model | string | 是 | 模型分组名（平台分配，如 `image2.5`） |
| prompt | string | 与 images 二选一 | 提示词，≤4000 字符 |
| n | int | 不要传 | **一个任务固定出一张图**。传 n>1 会被拒绝（400）；多张图请提交多个任务 |
| resolution | string | 否 | 清晰度档位 `1k`/`2k`/`4k`。**没传就不发该参数**，由模型用自己的默认 |
| aspectRatio | string | 否 | 方向意图 `W:H`。常见全集：1:1、2:3、3:2、3:4、4:3、4:5、5:4、9:16、16:9、21:9（具体由模型决定，超出返回上游明确错误）。**没传就不发尺寸参数**（修图保持参考图方向） |
| size | string | 不要传 | **已废弃**：旧版宽×高写法（如 `1024x1024`）。当前兼容接收并原样适配，新接入一律用 aspectRatio+resolution；`auto` 视为没传 |
| image | string | 否 | 参考图/底图 URL（**必须公网可访问**），等价于 images 的第一张 |
| images | string[] | 否 | 多张参考图 URL；数量上限由模型决定（如 gpt-image 系 14 张、nano 系更少），超出时上游返回明确错误，网关不代为限制 |
| options | object | 否 | 透传参数（quality、output_format 等，模型支持才生效） |

请求头（可选）：`X-Idempotency-Key: <业务幂等键>` —— 同应用同 key 重复提交返回同一任务，不重复扣费。

响应 `data`：

```json
{
  "taskId": "tsk_xxx_yyy",
  "model": "image2.5",
  "status": "SUBMITTED",
  "n": 1,
  "result": null,
  "error": null,
  "createdAt": "2026-10-08T18:00:00",
  "finishTime": null
}
```

### 分辨率契约：网关怎么适配上游

`aspectRatio`（方向意图 W:H）+ `resolution`（1k/2k/4k）是网关的统一参数，与背后哪家上游无关——网关负责翻译：

- **aspectRatio 支持集合由模型决定**：常见全集为 `1:1`、`2:3`、`3:2`、`3:4`、`4:3`、`4:5`、`5:4`、`9:16`、`16:9`、`21:9`（各模型略有差异，如部分模型不支持 4:5/5:4）。网关不代为限制，超出的比例会返回上游的明确错误。
- **resolution** 一律小写 `1k`/`2k`/`4k`（个别上游要求大写，网关自动纠正，业务方无感）。

| 你传 | 上游是 OpenAI 系（网关按方向归档成宽×高） | 上游是 Crun 系（原样直译，模型校验） |
|---|---|---|
| `16:9` + `2k` | size = 1536×1024（横版；该族 2k 自动就近降级） | aspect_ratio=16:9、resolution=2k |
| `9:16` + `4k` | size = 1024×1536（竖版） | aspect_ratio=9:16、resolution=4k |
| `3:4` + `2k` | size = 1024×1536（竖版方向归档） | aspect_ratio=3:4、resolution=2k |
| `21:9` + `1k` | size = 1536×1024（超宽按横版归档） | aspect_ratio=21:9、resolution=1k（模型不支持时上游明确报错） |
| `1:1` + `1k` | size = 1024×1024 | aspect_ratio=1:1、resolution=1k |
| 都不传 | 不带 size（模型用自己的默认，修图不裁方图） | 不带参数（模型默认） |

- 网关保证横竖不反：横版请求不会出竖图；超宽/超窄比例在 OpenAI 系按最近方向归档。
- 特殊模型的精确尺寸（如 1792×1024）由管理员按渠道配置映射，业务方无感；旧版 `size` 宽×高写法仍兼容（自动换算），新接入请统一用 `aspectRatio` + `resolution`。

> **统一异步**：提交只做受理，**立即返回任务号**（status=SUBMITTED，极快时可能已推进到 RUNNING），请求线程不等上游——无论背后是异步任务型上游还是同步出图型上游（同步等待由网关后台虚拟线程承担）。结果通过第 2 节查询接口轮询获取。

> **base64 结果说明**：部分模型（如 gpt-image 系）上游返回 base64 数据，此时 `result.urls` 为 `data:image/png;base64,...` 形式，查询接口同样能拿到（任务存档期内可查）。无论哪种形式都请及时下载转存。

## 2. 查询任务

```
GET /v1/images/tasks/{taskId}
```

响应 `data.status`：

| 状态 | 含义 |
|---|---|
| SUBMITTED | 已受理，待路由执行 |
| RUNNING | 执行中（上游生成中/轮询中） |
| SUCCEEDED | 成功，`result.urls` 可用 |
| FAILED | 失败，`error.code` / `error.message` 给出原因 |

成功响应示例：

```json
{
  "code": 0, "msg": "ok",
  "data": {
    "taskId": "tsk_xxx_yyy", "model": "image2.5", "status": "SUCCEEDED", "n": 1,
    "result": { "urls": ["https://.../image.png"] },
    "error": null,
    "createdAt": "2026-10-08T18:00:00", "finishTime": "2026-10-08T18:00:31"
  }
}
```

失败响应示例：

```json
{ "error": { "code": "CONTENT_REJECTED", "message": "提示词包含敏感内容" } }
```

## 3. 任务列表（本应用）

```
GET /v1/images/tasks?page=1&size=20&status=SUCCEEDED
```

## 4. 可用模型

```
GET /v1/images/models
→ data: [ { "model": "image2.5", "displayName": "Image 2.5", "price": 0.02, "strategy": "FAILOVER" } ]
```

---

## 错误码

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40001 | 参数不合法（模型不存在/prompt 缺失/参考图非公网 URL 等） |
| 401 | 40101 | 令牌无效/停用/过期 |
| 403 | 40301 | 令牌未授权该模型分组 |
| 404 | 40404 | 任务不存在 |
| 429 | 42901 | 触发限流，稍后重试（提交与查询分开计数：提交默认 120 次/分/令牌，查询默认 600 次/分/令牌） |
| 429 | 42902 | 该令牌排队任务已达上限（默认 20 条；等在途任务完成后再提交） |
| 500 | 50000 | 网关内部错误 |

## 排队与并发（v0.4）

- 任务提交后进入排队（SUBMITTED），派发器按**提交顺序**派发到有空闲并发的上游渠道；饱和时排队等待，**不因饱和失败**。
- 并发上限按**上游账号**（同 base_url + 同 API Key）计：账号下同时在跑的任务数不超过账号容量（管理员在渠道页配置）。
- 故障转移分组：1 号优先，饱和时排队等待；**出错**才切换下一成员。空闲优先分组：按顺序取第一个有空闲并发的成员。
- 排队不是无限的：默认排队上限 **30 分钟**（可配置），超时任务以 `QUEUE_WAIT_TIMEOUT` 失败。

## 任务失败 error.code

| code | 含义 | 是否计费 |
|---|---|---|
| CONTENT_REJECTED | 提示词/图片未通过上游内容审核 | 否 |
| PARAM_INVALID | 上游不支持的参数（如该尺寸） | 否 |
| UPSTREAM_TASK_FAILED | 上游生成失败（含上游原因） | 否 |
| UPSTREAM_TASK_LOST | 上游把任务弄丢了（连续多次查询 404；网关已做宽限，防上游受理瞬间的抖动误杀） | 否 |
| UPSTREAM_MODEL_UNAVAILABLE | 上游当前无账号/容量服务此模型（已自动换下一渠道重试） | 否 |
| UPSTREAM_BAD_RESPONSE | 上游返回 2xx 但响应无法解析（可能已出图；网关不换渠道重发，防重复扣费，请重新提交） | 否 |
| SYSTEM_SHUTDOWN | 网关发版停机中断提交（上游可能已受理；请重新提交） | 否 |
| TIMEOUT | 超过执行生命周期（默认 15 分钟，从领取到渠道起算） | 否 |
| QUEUE_WAIT_TIMEOUT | 排队等待超时（默认上限 30 分钟；渠道饱和或暂停导致） | 否 |
| UPSTREAM_ALL_FAILED | 重试后所有可用上游仍失败 | 否 |
| NO_AVAILABLE_CHANNEL | 该分组当前无可用上游 | 否 |
| CHANNEL_MISCONFIGURED | 任务所属渠道被删除/改协议，无法继续追踪 | 否 |
| UPSTREAM_MODEL_NOT_PERMITTED | 当前上游无此模型权限（已自动换下一渠道重试） | 否 |
| INTERNAL_ERROR | 网关内部连续异常（自动重跑超限；请检查后重新提交） | 否 |
| RESULT_TOO_LARGE | 生成结果体积超过网关存档上限（>15MB） | 否 |

（失败一律不计费。）

**调用建议**：提交后每 2~5 秒轮询一次查询接口；SUCCEEDED 后立即下载转存结果 URL（会过期）。
