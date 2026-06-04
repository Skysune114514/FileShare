-- H2 兼容脚本（MODE=MySQL），供测试启动时建表；字段与主库 schema 对齐

CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL,
    email           VARCHAR(128) NOT NULL,
    password_hash   VARCHAR(255) NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'user',
    CONSTRAINT uk_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS fs_node (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id          BIGINT       NOT NULL DEFAULT 0,
    name               VARCHAR(255) NOT NULL,
    node_type          VARCHAR(16)  NOT NULL,
    size_bytes         BIGINT,
    storage_key        VARCHAR(512),
    content_type       VARCHAR(128),
    owner_user_id      BIGINT       NOT NULL,
    uploaded_by_name   VARCHAR(128) NOT NULL,
    uploaded_by_user_id BIGINT,
    is_public          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fs_owner_parent_name UNIQUE (owner_user_id, parent_id, name)
);

CREATE TABLE IF NOT EXISTS fs_node_revision (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id              BIGINT       NOT NULL,
    storage_key          VARCHAR(512) NOT NULL,
    file_name_snapshot   VARCHAR(255) NOT NULL,
    size_bytes           BIGINT,
    content_type         VARCHAR(128),
    created_by_user_id   BIGINT       NOT NULL,
    created_by_name      VARCHAR(128) NOT NULL,
    created_at           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS project_member (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_root_node_id   BIGINT       NOT NULL,
    user_id                BIGINT       NOT NULL,
    role                   VARCHAR(32)  NOT NULL,
    created_at             TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_user UNIQUE (project_root_node_id, user_id)
);

CREATE TABLE IF NOT EXISTS api_access_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    duration_ms         INT          NOT NULL,
    http_method         VARCHAR(16)  NOT NULL,
    request_uri         VARCHAR(512) NOT NULL,
    query_string        VARCHAR(512),
    user_id             BIGINT,
    client_ip           VARCHAR(64),
    success             BOOLEAN      NOT NULL DEFAULT TRUE,
    http_status         INT,
    controller_method   VARCHAR(384) NOT NULL,
    error_message       VARCHAR(512)
);
