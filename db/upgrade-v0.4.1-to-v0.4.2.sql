-- v0.4.1 → v0.4.2：apiyi（gpt-image 系 + nano 系）接入前置
-- gpt-image / nano 的 4K 出图为 b64 内联（data URI 约 10-25MB），MEDIUMTEXT(16MB) 会截断成残废 JSON
ALTER TABLE generate_task
    MODIFY COLUMN result LONGTEXT NULL COMMENT '结果 JSON（4K b64 内联图可到 20MB+，不截断）';
