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
- **并发防护**：全局 + 每渠道两层并发闸；同步上游在提交请求内完成（虚拟线程阻塞等待零成本）。
- **上游 Key 安全**：AES-256-GCM 加密落库，管理端不回显；SK 只存哈希。
- **任务详情四件套**：重试时间线（每次调了哪个上游/什么错）+ 业务方请求体 + 上游请求体 + 响应结果。

## 发布与升级（发版前必读）

- **优雅停机**：停服时提交/轮询线程池会等在途任务最多 30 秒收尾（`spring.lifecycle.timeout-per-shutdown-phase=30s`），请用 `kill`（SIGTERM）正常停服，不要 `kill -9`。
- **对外契约变更**：`n` 参数已下线——一个任务固定出一张图，传 `n>1` 返回 400（多张图请提交多个任务）。
- **分组 DELETE = 真删除**（原为软停用）：不可恢复、有在途任务时拒绝。**前后端必须一起发布**，并提醒已打开旧控制台的同事强制刷新（Ctrl+F5），否则旧页面的"停用分组"按钮会调 DELETE 造成误删。
- **幂等键按令牌隔离**：唯一键从全局 `client_request_id` 改为 `(token_id, client_request_id)` 联合唯一，列大小写敏感（utf8mb4_bin）。跨令牌使用相同 key 不再互斥。存量库执行 `db/upgrade-20261011-idem-unique.sql`（改列类型会整表重建 generate_task，**建议停机窗口执行**；执行方式见脚本头注释）。
