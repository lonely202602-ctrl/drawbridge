-- drawbridge 升级脚本：幂等键按令牌隔离 + 大小写敏感（2026-10-11）
-- 适用：2026-10-10 之前建的存量库。新库直接用 schema.sql，无需本文件。
--
-- 执行方式（重要）：
--   mysql> USE drawbridge; SOURCE db/upgrade-20261011-idem-unique.sql;   ← 推荐，某条报错会继续执行后续语句
--   或：mysql --force -u... -p... drawbridge < db/upgrade-20261011-idem-unique.sql
--   ⚠ 不带 --force 的 `mysql drawbridge < file` 遇到第一个错误会整体退出，造成半升级。
-- 已执行过对应变更时会报 Duplicate column/key name，属预期，跳过该条即可。

-- ① 历史空白键归零（空白不应占唯一键，也不该被静默复用）
UPDATE generate_task SET client_request_id = NULL
WHERE client_request_id IS NOT NULL AND TRIM(client_request_id) = '';

-- ② 唯一键从全局 (client_request_id) 改为按令牌隔离 (token_id, client_request_id)，
--    列排序规则改 utf8mb4_bin（大小写敏感，Order-1 与 order-1 视为不同键）。
--    三项合并为一条 ALTER：generate_task 只重建一次（MODIFY 类型必然重建，期间锁写，建议停机窗口执行）。
ALTER TABLE generate_task
    MODIFY COLUMN client_request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    DROP INDEX uk_client_request,
    ADD UNIQUE KEY uk_token_request (token_id, client_request_id);
