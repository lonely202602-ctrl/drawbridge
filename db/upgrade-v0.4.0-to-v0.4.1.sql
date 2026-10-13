-- v0.4.1 升级脚本（交叉测试修复批）
-- 分组成员停用出处：cascade=渠道/账号停用联动（渠道启用/批次恢复会自动复活）；
-- admin=管理员手动停用（永不被自动恢复）；NULL=历史停用数据，按手动处理（宁可不恢复，不可误恢复）。
ALTER TABLE group_member
    ADD COLUMN disable_source VARCHAR(16) NULL AFTER enabled;

-- v0.4.0 期间自动停用（status=4）当时不改 enabled，v0.4.1 起自动停用同时落 enabled=0；
-- 补齐存量：仍是 status=4 的渠道与新逻辑对齐（管理端"启用"/整批恢复即可带回 enabled=1）。
UPDATE channel SET enabled = 0 WHERE status = 4 AND enabled = 1;
