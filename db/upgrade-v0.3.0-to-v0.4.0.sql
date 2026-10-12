-- drawbridge v0.3.0 → v0.4.0 升级脚本
-- 主题：DB 槽位并发（队列=SUBMITTED 行，槽位=账号下 RUNNING 行）+ 空闲优先策略 + 账号级余额关停
-- 说明：
--   * account_key 由应用在 Java 侧计算（apiKey 是 AES-GCM 密文，SQL 算不了），
--     应用启动时自动为存量渠道补算并写入本列，同时惰性创建 upstream_account 行。
--   * upstream_account.capacity 初始值取同账号各渠道 max_concurrency 的最小值（保守取值）。
--   * route.global_concurrency 语义变化：原"上游 HTTP 调用并发上限"→ 现"全网在途任务上限"，
--     默认 64 → 256（账号容量之和通常远小于此，仅兜底）。下面仅在仍为旧默认值时改写。

USE drawbridge;

-- 1) 渠道表：账号键列
ALTER TABLE channel
    ADD COLUMN account_key VARCHAR(64) NULL COMMENT '上游账号键 sha256(归一化base_url|apiKey) 前 32 位，保存渠道时计算' AFTER max_concurrency,
    ADD KEY idx_account (account_key);

-- 2) 上游账号（同 Key 渠道共享并发池，容量显式配置，0=暂停派发）
CREATE TABLE IF NOT EXISTS upstream_account (
    account_key VARCHAR(64)  PRIMARY KEY COMMENT 'sha256(归一化base_url|apiKey) 前 32 位',
    capacity    INT          NOT NULL DEFAULT 5 COMMENT '该账号同时在跑任务数上限，0=暂停派发',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3) 账号级批量关停批次（余额耗尽等，充值后一键整批恢复）
CREATE TABLE IF NOT EXISTS disable_batch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_key VARCHAR(64)  NOT NULL,
    reason      VARCHAR(255) NOT NULL,
    channel_ids TEXT         NOT NULL COMMENT '本次关停的渠道 id JSON 数组',
    member_ids  TEXT         NOT NULL COMMENT '本次联动停用的分组成员 id JSON 数组',
    restored_at DATETIME     NULL COMMENT '非空=已整批恢复',
    created_at  DATETIME     NOT NULL,
    KEY idx_account (account_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 4) 派发器按分组取最老排队任务的索引
ALTER TABLE generate_task ADD KEY idx_group_status (group_code, status);

-- 5) 全局闸语义重述（仅在仍是旧默认值 64 时改写；管理员已自定义的不动）
UPDATE system_config SET config_value = '256'
WHERE config_key = 'route.global_concurrency' AND config_value = '64';
