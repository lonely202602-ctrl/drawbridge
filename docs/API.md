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
| resolution | string | 否 | 清晰度档位 `1k`/`2k`/`4k`，默认 1k；上游不支持高档时自动就近降级 |
| aspectRatio | string | 否 | 方向意图 `W:H`（如 `16:9`、`9:16`），保证横竖不反；默认 `1:1` |
| image | string | 否 | 参考图/底图 URL（**必须公网可访问**），等价于 images 的第一张 |
| images | string[] | 否 | 多张参考图 URL，最多 4 张 |
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
| 500 | 50000 | 网关内部错误 |

## 任务失败 error.code

| code | 含义 | 是否计费 |
|---|---|---|
| CONTENT_REJECTED | 提示词/图片未通过上游内容审核 | 否 |
| PARAM_INVALID | 上游不支持的参数（如该尺寸） | 否 |
| UPSTREAM_TASK_FAILED | 上游生成失败（含上游原因） | 否 |
| TIMEOUT | 超过最大生命周期（默认 15 分钟） | 否 |
| UPSTREAM_ALL_FAILED | 重试后所有可用上游仍失败 | 否 |
| NO_AVAILABLE_CHANNEL | 该分组当前无可用上游 | 否 |
| UPSTREAM_MODEL_NOT_PERMITTED | 当前上游无此模型权限（已自动换下一渠道重试） | 否 |

（失败一律不计费。）

**调用建议**：提交后每 2~5 秒轮询一次查询接口；SUCCEEDED 后立即下载转存结果 URL（会过期）。
