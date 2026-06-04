package org.example.fileshare1.entity;

// 接口访问日志实体：每次业务请求结束后由 AOP 写入一行；
// 被过滤器 401/403 拦下的请求由过滤器补写一行。

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 接口访问日志实体，对应表 {@code api_access_log}。
 * 由 {@link org.example.fileshare1.aop.ApiAccessLogAspect} 在 Controller 返回前后同步 insert；
 * 被鉴权过滤器直接拒绝（401/403）的请求由 {@link org.example.fileshare1.security.ApiSessionAuthFilter} 补记。
 * 管理员通过 {@link org.example.fileshare1.controller.AdminController#accessLogs} 分页查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("api_access_log")
public class ApiAccessLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime occurredAt;
    private Integer durationMs;
    private String httpMethod;
    private String requestUri;
    private String queryString;
    private Long userId;
    private String clientIp;
    private Boolean success;
    private Integer httpStatus;
    private String controllerMethod;
    private String errorMessage;
}
