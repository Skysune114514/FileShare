-- 文件树元数据表；物理文件存放在配置项 file.storage.root 下
-- learn 环境下由 spring.sql.init 在启动时执行（CREATE IF NOT EXISTS，可重复执行）
-- 若你曾用旧版无 owner 字段的表，请手动：DROP TABLE fs_node; 后重启以重建

CREATE TABLE IF NOT EXISTS fs_node (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id          BIGINT       NOT NULL DEFAULT 0 COMMENT '0=用户空间根；根下的一级文件夹为「项目」，文件应挂在项目内',
    name               VARCHAR(255) NOT NULL,
    node_type          VARCHAR(16)  NOT NULL COMMENT 'FOLDER 或 FILE',
    size_bytes         BIGINT       NULL,
    storage_key        VARCHAR(512) NULL COMMENT 'FILE 时相对存储根的路径',
    content_type       VARCHAR(128) NULL,
    owner_user_id      BIGINT       NOT NULL COMMENT '归属用户，隔离命名空间',
    uploaded_by_name   VARCHAR(128) NOT NULL COMMENT '创建/上传时的用户署名快照',
    uploaded_by_user_id BIGINT      NULL COMMENT '创建/上传时的用户 id 快照（老数据可为空，删除鉴权优先用 id）',
    is_public          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=广场对他人可见（列表/进目录/下载）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_parent_name (owner_user_id, parent_id, name),
    KEY idx_owner_parent (owner_user_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
