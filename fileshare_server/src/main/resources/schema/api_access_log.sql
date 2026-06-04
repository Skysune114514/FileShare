-- HTTP 访问日志（由 AOP 写入）；learn 环境随 spring.sql.init 执行

CREATE TABLE IF NOT EXISTS api_access_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求结束落库时间',
    duration_ms     INT          NOT NULL COMMENT 'Controller 方法耗时',
    http_method     VARCHAR(16)  NOT NULL,
    request_uri     VARCHAR(512) NOT NULL,
    query_string    VARCHAR(512) NULL,
    user_id         BIGINT       NULL COMMENT '会话用户 id（过滤器写入 request 后由日志组件读取），匿名接口为空',
    client_ip       VARCHAR(64)  NULL,
    success         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=无异常返回 0=抛错',
    http_status     INT          NULL COMMENT '若返回 ResponseEntity 则记录状态码',
    controller_method VARCHAR(384) NOT NULL COMMENT '类名.方法名',
    error_message   VARCHAR(512) NULL,
    KEY idx_occurred_at (occurred_at),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
