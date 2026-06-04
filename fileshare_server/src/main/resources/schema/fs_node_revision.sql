-- 文件内容历史快照（修改前写入）；每节点最多保留 20 条由应用层裁剪
CREATE TABLE IF NOT EXISTS fs_node_revision (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id              BIGINT       NOT NULL COMMENT '对应 fs_node.id，仅 FILE',
    storage_key          VARCHAR(512) NOT NULL COMMENT '快照二进制相对 file.storage.root',
    file_name_snapshot   VARCHAR(255) NOT NULL COMMENT '写入快照时的展示文件名',
    size_bytes           BIGINT       NULL,
    content_type         VARCHAR(128) NULL,
    created_by_user_id   BIGINT       NOT NULL,
    created_by_name      VARCHAR(128) NOT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_node_created (node_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
