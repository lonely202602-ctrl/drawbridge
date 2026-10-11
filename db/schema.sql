-- drawbridge 数据库表结构（MySQL 8）
-- 初始化：
--   mysql> CREATE DATABASE IF NOT EXISTS drawbridge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
--   mysql> USE drawbridge;
--   mysql> SOURCE db/schema.sql;

-- 上游渠道：一家上游中转站的一个接入配置
CREATE TABLE IF NOT EXISTS channel (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL,
    protocol        VARCHAR(32)  NOT NULL COMMENT 'OPENAI_IMAGES_SYNC/OPENAI_ASYNC_TASK/CRUN_TASK/VOLCANO_ARK',
    base_url        VARCHAR(255) NOT NULL,
    api_key_enc     TEXT         NOT NULL COMMENT 'AES-GCM 加密后的上游 Key',
    path_overrides  TEXT         NULL COMMENT '路径覆盖 JSON',
    extra_config    TEXT         NULL COMMENT '适配参数 JSON（异步协议字段指针等）',
    max_concurrency INT          NULL DEFAULT 5 COMMENT '单渠道并发闸，空=默认',
    cost_price      DECIMAL(12, 4) NULL COMMENT '上游成本单价/张（对账用）',
    enabled         TINYINT      NOT NULL DEFAULT 1,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常/2熔断/3半开/4余额耗尽停用/0手动停用',
    status_reason   VARCHAR(255) NULL,
    status_time     DATETIME     NULL,
    remark          VARCHAR(255) NULL,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    KEY idx_status (enabled, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 渠道上游模型清单（同步/人工录入）
CREATE TABLE IF NOT EXISTS upstream_model (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id  BIGINT       NOT NULL,
    model_code  VARCHAR(128) NOT NULL,
    source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1同步/2人工',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_channel_model (channel_id, model_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 模型分组 = 业务方调用的 model 名
CREATE TABLE IF NOT EXISTS model_group (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code   VARCHAR(64)  NOT NULL,
    display_name VARCHAR(64)  NULL,
    strategy     VARCHAR(16)  NOT NULL DEFAULT 'FAILOVER' COMMENT 'POLLING/RANDOM/FAILOVER/WEIGHTED',
    biz_price    DECIMAL(12, 4) NOT NULL DEFAULT 0 COMMENT '我方售价/张',
    enabled      TINYINT      NOT NULL DEFAULT 1,
    remark       VARCHAR(255) NULL,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    UNIQUE KEY uk_group_code (group_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 分组成员条目
CREATE TABLE IF NOT EXISTS group_member (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id       BIGINT       NOT NULL,
    channel_id     BIGINT       NOT NULL,
    upstream_model VARCHAR(128) NOT NULL,
    weight         INT          NOT NULL DEFAULT 1,
    sort_order     INT          NOT NULL DEFAULT 0,
    enabled        TINYINT      NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    KEY idx_group (group_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 业务方令牌（SK 只显一次，库存哈希+前缀）
CREATE TABLE IF NOT EXISTS api_token (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(64)   NOT NULL,
    sk_hash        CHAR(64)      NOT NULL,
    sk_prefix      VARCHAR(16)   NOT NULL,
    sk_enc         TEXT          NULL COMMENT 'AES 加密的完整 SK（支持事后查看）',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '1启用/0禁用',
    quota_total    DECIMAL(12, 2) NULL COMMENT '总额度，NULL=不限',
    quota_used     DECIMAL(12, 2) NOT NULL DEFAULT 0,
    allowed_groups TEXT          NULL COMMENT 'JSON 数组，空=不限分组',
    expires_at     DATETIME      NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME      NOT NULL,
    UNIQUE KEY uk_sk_hash (sk_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 生成任务（DB 当队列，真相源）
CREATE TABLE IF NOT EXISTS generate_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id           VARCHAR(40)  NOT NULL,
    token_id          BIGINT       NOT NULL,
    group_code        VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/RUNNING/SUCCEEDED/FAILED',
    image_count       INT          NOT NULL DEFAULT 1,
    biz_request       MEDIUMTEXT   NULL COMMENT '业务方原始请求体（完整存档，执行入参来源）',
    upstream_request  TEXT         NULL COMMENT '发上游的封装请求体（截断存档）',
    upstream_url      VARCHAR(512) NULL COMMENT '最后一次提交尝试命中的上游完整URL',
    result            MEDIUMTEXT   NULL COMMENT '结果 JSON（b64 内联图最大约 2MB/张，不截断）',
    error_code        VARCHAR(40)  NULL,
    error_msg         VARCHAR(512) NULL,
    channel_id        BIGINT       NULL,
    upstream_task_id  VARCHAR(128) NULL,
    attempt_count     INT          NOT NULL DEFAULT 0,
    poll_count        INT          NOT NULL DEFAULT 0,
    rerun_count       INT          NOT NULL DEFAULT 0 COMMENT '提交链路回退重跑计数（退避与5次上限用，受理成功清零）',
    not_found_count   INT          NOT NULL DEFAULT 0 COMMENT '轮询期连续404计数（任务丢失宽限判据，非404轮询即清零）',
    last_poll_at      DATETIME     NULL COMMENT '最近一次轮询上游的时间（轮询器领取即写）',
    next_poll_time    DATETIME     NOT NULL,
    expires_at        DATETIME     NOT NULL,
    freeze_amount     DECIMAL(12, 4) NOT NULL DEFAULT 0,
    settle_amount     DECIMAL(12, 4) NULL,
    client_request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    finish_time       DATETIME     NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_token_request (token_id, client_request_id),
    KEY idx_status_nextpoll (status, next_poll_time),
    KEY idx_expires (status, expires_at),
    KEY idx_token_created (token_id, created_at),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 任务尝试流水（重试时间线）
CREATE TABLE IF NOT EXISTS task_attempt (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id        BIGINT       NOT NULL COMMENT 'generate_task.id',
    attempt_no     INT          NOT NULL,
    channel_id     BIGINT       NOT NULL,
    upstream_model VARCHAR(128) NOT NULL,
    upstream_url   VARCHAR(512) NULL COMMENT '本次提交尝试命中的上游URL',
    phase          VARCHAR(16)  NOT NULL COMMENT 'SUBMIT/POLL',
    outcome        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
    error_kind     VARCHAR(32)  NULL,
    http_status    INT          NULL,
    error_msg      VARCHAR(512) NULL,
    cost_ms        INT          NULL,
    created_at     DATETIME     NOT NULL,
    KEY idx_task (task_id, attempt_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 用量流水
CREATE TABLE IF NOT EXISTS usage_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(40)    NOT NULL,
    token_id    BIGINT         NOT NULL,
    group_code  VARCHAR(64)    NOT NULL,
    channel_id  BIGINT         NULL,
    success     TINYINT        NOT NULL,
    image_count INT            NOT NULL DEFAULT 1,
    biz_amount  DECIMAL(12, 4) NOT NULL DEFAULT 0,
    cost_amount DECIMAL(12, 4) NOT NULL DEFAULT 0,
    error_kind  VARCHAR(32)    NULL,
    created_at  DATETIME       NOT NULL,
    KEY idx_token (token_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 运营参数（热更新）
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(64)  NOT NULL PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    description  VARCHAR(255) NULL,
    updated_at   DATETIME     NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 管理端账号（首启自动种子 admin/admin123）
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
