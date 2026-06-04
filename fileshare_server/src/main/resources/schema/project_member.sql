-- 项目成员：关联空间根下的一级「项目」文件夹（fs_node.id）与用户；访客不入表，公开读由 is_public 控制
CREATE TABLE IF NOT EXISTS project_member (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_root_node_id   BIGINT       NOT NULL COMMENT 'fs_node 中 parent_id=0 的一级项目文件夹 id',
    user_id                BIGINT       NOT NULL,
    role                   VARCHAR(32)  NOT NULL COMMENT 'member 或 project_admin',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_user (project_root_node_id, user_id),
    KEY idx_pm_user (user_id),
    CONSTRAINT fk_pm_project FOREIGN KEY (project_root_node_id) REFERENCES fs_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
