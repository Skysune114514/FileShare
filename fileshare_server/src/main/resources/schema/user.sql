-- 用户表，与实体 org.example.fileshare1.entity.User 一致（MyBatis-Plus 表名 `user`）
-- learn 环境下由 spring.sql.init 执行；须先于 fs_node.sql（文件树可引用 owner_user_id）
-- 若库中已有旧结构表，请先备份后迁移（role、删除 age 等）

CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(64)  NOT NULL COMMENT '昵称/展示名，用于文件署名快照',
    email           VARCHAR(128) NOT NULL COMMENT '登录邮箱',
    password_hash   VARCHAR(255) NULL COMMENT 'BCrypt 密文；新注册用户写入，历史数据可为空',
    role            VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT 'user=普通用户 admin=管理员',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_name (name(32))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户';
