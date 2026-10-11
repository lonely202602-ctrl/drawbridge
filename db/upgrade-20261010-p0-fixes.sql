-- drawbridge 升级脚本：v0.2.0 → P0 修复批次（2026-10-10）
-- 适用：用旧版 schema.sql 建的存量库。新库直接执行 schema.sql，无需本文件。
-- 幂等性：列/索引已存在时会报 Duplicate column / Duplicate key name，跳过对应语句即可。
--   mysql> USE drawbridge;
--   mysql> SOURCE db/upgrade-20261010-p0-fixes.sql;

-- ① biz_request 改 MEDIUMTEXT：业务方请求体完整存档（执行链路从它解析入参，截断会丢参数）
ALTER TABLE generate_task
    MODIFY COLUMN biz_request MEDIUMTEXT NULL COMMENT '业务方原始请求体（完整存档，执行入参来源）';

-- ② 任务与尝试流水记录命中上游的完整 URL（2026-10-10 批次）
ALTER TABLE generate_task
    ADD COLUMN upstream_url VARCHAR(512) NULL COMMENT '最后一次提交尝试命中的上游完整URL' AFTER upstream_request;
ALTER TABLE task_attempt
    ADD COLUMN upstream_url VARCHAR(512) NULL COMMENT '本次提交尝试命中的上游URL' AFTER upstream_model;

-- ③ 仪表盘按天计数用（created_at 无索引会全表扫描）
ALTER TABLE generate_task ADD KEY idx_created (created_at);
