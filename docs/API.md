# drawbridge 对外 API 文档（业务方版）

Base URL：`http://<网关地址>:8180`

统一响应壳：

```json
{ "code": 0, "msg": "ok", "data": { ... } }
```

- `code = 0` 成功；非 0 见文末错误码表。
- 鉴权：所有接口携带 `Authorization: Bearer sk-xxxx`（SK 在 drawbridge 平台创建，只显示一次）。
- 结果不落盘：生成的图片/视频 URL 为上游原样返回，**有有效期**，请及时取用并转存到你方存储。

---

## 1. 提交生图任务

```
POST /v1/images/tasks
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| model | string | 是 | 模型分组名（平台分配，如 `image2.5`） |
| prompt | string | 与 image 二选一 | 提示词，≤4000 字符 |
| n | int | 否 | 张数，1-6，默认 1（按 n 计费） |
| size | string | 否 | 如 `1024x1024`（是否支持取决于模型） |
| aspectRatio | string | 否 | 如 `3:2`（部分模型支持） |
| image | string | 否 | 参考图/底图 URL（**必须公网可访问**） |
| images | string[] | 否 | 多张参考图 URL，最多 4 张 |
| options | object | 否 | 透传参数（quality、output_format 等，模型支持才生效） |

请求头（可选）：`X-Idempotency-Key: <业务幂等键>` —— 同应用同 key 重复提交返回同一任务，不重复扣费。

响应 `data`：

```json
{
  "taskId": "tsk_xxx_yyy",
  "model": "image2.5",
  "status": "RUNNING",
  "n": 1,
  "result": null,
  "error": null,
  "charge": 0.02,
  "createdAt": "2026-10-08T18:00:00",
  "finishTime": null
}
```

> **同步/异步说明**：网关统一任务契约。背后是异步上游时提交秒回（status=SUBMITTED/RUNNING）；
> 背后是同步上游时，本请求会等到出图才返回（status 直接为 SUCCEEDED，result 内带结果）。

> **base64 结果说明**：部分模型（如 gpt-image 系）上游只返回 base64 数据，此时结果**只随提交响应返回**
> （result.urls 为 `data:image/png;base64,...` 形式），请直接保存；网关不落盘，事后查询接口取不到该数据。

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
    "error": null, "charge": 0.02,
    "createdAt": "2026-10-08T18:00:00", "finishTime": "2026-10-08T18:00:31"
  }
}
```

失败响应示例（内容类错误**不扣费**）：

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

## 5. 余额

```
GET /v1/balance
→ data: { "quotaTotal": 100.00, "quotaUsed": 3.14, "unit": "元" }
```

失败任务自动退款（额度返还）。

---

## 错误码

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40001 | 参数不合法（模型不存在/prompt 缺失/参考图非公网 URL 等） |
| 401 | 40101 | 令牌无效/停用/过期 |
| 402 | 40201 | 额度不足 |
| 403 | 40301 | 令牌未授权该模型分组 |
| 404 | 40404 | 任务不存在 |
| 429 | 42901 | 触发限流（默认 120 次/分钟/令牌），稍后重试 |
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

**调用建议**：提交后每 2~5 秒轮询一次查询接口；SUCCEEDED 后立即下载转存结果 URL（会过期）。
