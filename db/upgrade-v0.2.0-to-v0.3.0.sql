-- drawbridge 升级脚本：v0.2.0 → v0.3.0（合并发布，2026-10）
-- 适用：用 v0.2.0（cd59d2e）的 schema.sql 建的存量库。新库直接执行 schema.sql，无需本文件。
-- 三批变更（P0 修复 / 幂等 v2 / 计数拆列）合并进一条 generate_task 大表 ALTER：
-- MODIFY 必然触发表重建，合并成一条只重建一次（拆成多条会各重建一次，白锁两回）。
--
-- 执行方式（重要）：
--   mysql> USE drawbridge; SOURCE db/upgrade-v0.2.0-to-v0.3.0.sql;   ← 推荐，某条报错会继续执行后续语句
--   或：mysql --force -u... -p... drawbridge < db/upgrade-v0.2.0-to-v0.3.0.sql
--   ⚠ 不带 --force 的 `mysql drawbridge < file` 遇到第一个错误会整体退出，造成半升级。
-- 已执行过的变更会报 Duplicate column/key name，属预期，跳过该条即可。

-- ① 历史空白幂等键归零（空白键不该占唯一键，也不该被静默复用）
UPDATE generate_task SET client_request_id = NULL
WHERE client_request_id IS NOT NULL AND TRIM(client_request_id) = '';

-- ② generate_task 一次性重整（表重建，期间锁写，建议停机窗口执行）：
--    - biz_request MEDIUMTEXT：业务方请求体完整存档（执行链路从它解析入参，截断丢参数）
--    - upstream_url：最后一次提交尝试命中的上游完整 URL（存档展示与排障）
--    - client_request_id utf8mb4_bin：幂等键大小写敏感（Order-1 与 order-1 是不同键）
--    - 唯一键从全局 (client_request_id) 改为按令牌隔离 (token_id, client_request_id)：
--      原全局唯一会让两个令牌用同一个键时互相顶掉任务
--    - rerun_count：提交链路回退重跑计数（回退退避与 5 次上限用它，与轮询次数 poll_count 语义分离）
--    - not_found_count：轮询期连续 404 计数（任务丢失宽限判据，非 404 轮询即清零）
--    - idx_created：仪表盘按天计数用（无索引会全表扫描）
ALTER TABLE generate_task
    MODIFY COLUMN biz_request MEDIUMTEXT NULL COMMENT '业务方原始请求体（完整存档，执行入参来源）',
    ADD COLUMN upstream_url VARCHAR(512) NULL COMMENT '最后一次提交尝试命中的上游完整URL' AFTER upstream_request,
    MODIFY COLUMN client_request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    DROP INDEX uk_client_request,
    ADD UNIQUE KEY uk_token_request (token_id, client_request_id),
    ADD COLUMN rerun_count INT NOT NULL DEFAULT 0 COMMENT '提交链路回退重跑计数（退避与5次上限用）' AFTER poll_count,
    ADD COLUMN not_found_count INT NOT NULL DEFAULT 0 COMMENT '轮询期连续404计数（任务丢失宽限判据）' AFTER rerun_count,
    ADD COLUMN last_poll_at DATETIME NULL COMMENT '最近一次轮询上游的时间' AFTER not_found_count,
    ADD KEY idx_created (created_at);

-- ③ task_attempt 补上游 URL（重试时间线展示每次实际命中的地址）
ALTER TABLE task_attempt
    ADD COLUMN upstream_url VARCHAR(512) NULL COMMENT '本次提交尝试命中的上游URL' AFTER upstream_model;
