# drawbridge 🌉

图片/视频中转网关（生图先行，视频后置）。业务方只看到一套任务式 API 与自定义模型名；
上游中转站对业务方完全不可见，路由、重试、熔断、余额停用全部由网关内部消化。

## 核心原则

1. **结果不落盘**：入参要求公网 URL，出参原样透传上游结果（网关不转存文件）。
2. **统一任务契约**：`提交拿 taskId → 轮询拿结果`，同步/异步上游差异由网关吃掉。
3. **分组即模型**：业务方 model 名 = 分组；分组挂多渠道成员（故障转移/轮询/随机/加权）。
4. **渠道的病换渠道重试，请求的病直接返回**：没米/Key 失效 → 渠道自动停用+告警（充值后一键恢复）；
   429 → 短冷却；5xx/超时 → 熔断计数；提示词/内容/参数错误 → 不重试，作为任务结果返回并退款。

## 技术栈

Java 21（虚拟线程）· Spring Boot 3.5 MVC · MyBatis-Plus · MySQL 8 · Vue 3 + Element Plus
（v1 不引 Redis：单实例部署，任务防重领用 DB 原子更新实现）

## 目录结构

```
backend/   Spring Boot 单体（adapter 4协议适配 / route 路由 / task 任务流水线
           / billing 计量 / health 熔断并发闸 / openapi 业务方API / admin 管理端API）
web/       Vue 3 管理/监控控制台（构建产物 dist/ 由后端同源托管）
db/        schema.sql（11 张表）
docs/      API.md 业务方接口文档
```

## 快速启动

```bash
# 0) 准备数据库（MySQL 8，用已有实例即可，库独立不碰其它数据）
mysql> CREATE DATABASE IF NOT EXISTS drawbridge DEFAULT CHARACTER SET utf8mb4;
mysql> USE drawbridge; SOURCE db/schema.sql;

# 1) 启动后端（JDK 21 必须；本机 IDEA 下载的 JDK 在 C:\Users\<你>\.jdks\ms-21.0.11）
cd backend
DRAWBRIDGE_DB_PASSWORD=<你的MySQL密码> mvn spring-boot:run
# 首启自动：种子 25 项运营参数 + 默认管理员 admin/admin123（请尽快改密）

# 2) 管理控制台
#    http://localhost:8180  （Vue 已构建时直接同源访问；改了前端需重新构建）
cd web && npm install && npm run build

# 3) 接入一个上游（控制台 → 上游渠道）：
#    选协议族 → 填 base_url + API Key → 【试跑】通过 → 同步模型（Crun 人工录入）
#    → 模型分组：建分组（model 名）→ 挂成员、选策略、定售价
#    → 应用与令牌：发 SK（只显示一次）→ 业务方按 docs/API.md 调用
```

## 运行测试

```bash
# 全量 60 个用例（50 单元 + 10 全链路集成；集成测试用本地 MySQL 的 drawbridge_test 独立库）
cd backend
DRAWBRIDGE_DB_PASSWORD=<你的MySQL密码> mvn test
```

> 真实上游（真金）调用测试不在日常测试里——配置好真实渠道后，用控制台的【试跑】与
> 一个小额任务验证即可。

## 运营参数（控制台 → 设置，热更新生效）

重试渠道数/时间预算、熔断阈值、429 冷却、轮询间隔上下限/批次/租约、任务最大生命周期、
停滞清扫、数据保留天数、请求体存档上限、业务方限流。yml 仅含数据库/加密密钥等基础设施项。

## 关键机制速查

- **任务追踪 = DB 当队列**：`generate_task(status, next_poll_time)`；执行器/轮询器双虚拟线程流水线；
  领取即租约（原子 UPDATE），进程崩溃不丢任务，兜底清扫 + 生命周期超时保证任务必达终态。
- **指数退避**：轮询间隔从 `poll.initial_interval_ms` 起 ×2，封顶 `poll.max_interval_ms`（设置页可调）。
- **并发防护**：DB 槽位账本（v0.4）——账号级真实在途并发 + 全网在途上限 + 排队（SUBMITTED 行即队列）；同步上游在提交请求内完成（虚拟线程阻塞等待零成本）。
- **上游 Key 安全**：AES-256-GCM 加密落库，管理端不回显；SK 只存哈希。
- **任务详情四件套**：重试时间线（每次调了哪个上游/什么错）+ 业务方请求体 + 上游请求体 + 响应结果。

## 发布与升级（发版前必读）

- **优雅停机**：停服时提交/轮询线程池会等在途任务最多 30 秒收尾（`spring.lifecycle.timeout-per-shutdown-phase=30s`），请用 `kill`（SIGTERM）正常停服，不要 `kill -9`。
- **对外契约变更**：`n` 参数已下线——一个任务固定出一张图，传 `n>1` 返回 400（多张图请提交多个任务）。
- **分组 DELETE = 真删除**（原为软停用）：不可恢复、有在途任务时拒绝。**前后端必须一起发布**，并提醒已打开旧控制台的同事强制刷新（Ctrl+F5），否则旧页面的"停用分组"按钮会调 DELETE 造成误删。
- **幂等键按令牌隔离**：唯一键从全局 `client_request_id` 改为 `(token_id, client_request_id)` 联合唯一，列大小写敏感（utf8mb4_bin）。跨令牌使用相同 key 不再互斥。
- **v0.3.0 升级脚本已三合一**：存量 v0.2.0 库执行 `db/upgrade-v0.2.0-to-v0.3.0.sql`（biz_request MEDIUMTEXT / upstream_url / 幂等键联合唯一 / rerun_count 与 not_found_count 拆列合并为一条 generate_task ALTER，**只整表重建一次，建议停机窗口执行**；执行方式见脚本头注释）。
- **删除分组的令牌级联语义**：只授权了被删分组的令牌会写成 `[]`（零权限），管理端显示"无授权分组"；**不会**变成"全部分组"。重建同名分组也不会让这些令牌自动拿回权限，需重新授权。
- **尺寸契约**：`aspectRatio`/`resolution` 不传就**不发对应上游参数**（模型用自己的默认；修图保持参考图方向）。旧版 `size` 参数进入 deprecated：格式合法（宽x高，大小写 X 均可，`auto` 视为没传）则原样透传给 OpenAI 系上游、换算给 Crun 系；非法格式返回 400。渠道 `extra_config.sizeMap` 支持按方向配：`{"2k":{"landscape":"2048x1152","portrait":"1152x2048","square":"2048x2048"}}`（字符串形式=整表覆盖仍兼容）。
- **发版中断语义**：停机强制中断时，已向上游发过请求的任务判 `SYSTEM_SHUTDOWN` 终态（不回退重发，防重复扣费），没发过请求的安全回退重跑。**编排平台的终止宽限期需 ≥90 秒**（web 30s + 提交 30s + 轮询 30s），否则请在设置页调小等待。
- **v0.4.0（DB 槽位并发）**：
  - 并发模型重做：**队列 = SUBMITTED 任务行，槽位 = 上游账号下 RUNNING 行**。并发上限从"只罩提交那一下"变为**真实在途并发**（提交上游起、终态释放），账号 = 同 base_url+API Key，同账号渠道共享一个并发池，容量在渠道页配置（**0 = 暂停派发**）。
  - 饱和语义：所有渠道跑满时任务**排队等待不失败**（严格按提交顺序派发，新提交不插队）；排队上限默认 30 分钟（`task.queue_max_wait_ms`），超时判 `QUEUE_WAIT_TIMEOUT`；单令牌排队上限默认 20 条（`token.max_queued`），超出提交即拒 `TOO_MANY_QUEUED`。
  - 新增**空闲优先**策略（按成员顺序取第一个有空闲并发的成员）；故障转移回归本义=出错才切换（饱和时排队等待）。
  - 余额耗尽升级为**账号级批量停用**（只认精确余额信号），关停批次记录在案，充值后渠道页/队列监控**一键整批恢复**；单渠道启用会联动恢复其被停用的分组成员。
  - 全网在途上限 `route.global_concurrency` 默认 64 → 256（语义变为"全网在途任务数"）；`route.permit_wait_ms` / `route.time_budget_ms` 废弃。
  - **升级**：存量 v0.3.0 库执行 `db/upgrade-v0.3.0-to-v0.4.0.sql`（channel 加 account_key 列 + upstream_account / disable_batch 两张新表 + 分组索引，一条 ALTER，秒级）。account_key 由应用启动时自动补算，**初始化容量取同账号各渠道 max_concurrency 的最小值**，之后以 upstream_account 为准（渠道上的 max_concurrency 列废弃只读）。
