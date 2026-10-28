-- 第9卷扩展：微信小程序登录
-- 1) 微信用户可能没有手机号，放开 phone 的 NOT NULL（原唯一索引保留，MySQL 允许多个 NULL）
ALTER TABLE uc_user MODIFY COLUMN phone VARCHAR(32) NULL;
-- 2) 增加微信身份字段；openid 唯一，保证一个微信用户只对应一个账号
ALTER TABLE uc_user ADD COLUMN openid VARCHAR(64) NULL AFTER phone,
                    ADD COLUMN unionid VARCHAR(64) NULL AFTER openid;
CREATE UNIQUE INDEX uk_uc_user_openid ON uc_user (openid);
