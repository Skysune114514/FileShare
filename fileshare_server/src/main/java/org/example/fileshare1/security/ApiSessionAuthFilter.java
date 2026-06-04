package org.example.fileshare1.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.entity.ApiAccessLog;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 全局接口鉴权过滤器（自研“轻量 Spring Security”）。
 *
 * 每个 /api 请求进门时都会先经过这里。它只回答三个问题：
 * 1. 这个接口允不允许匿名访问？  —— 看白名单；
 * 2. 当前有没有登录？            —— 看 Redis Session 里有没有 FS_UID；
 * 3. 是不是管理员专属接口？       —— /api/admin/** 要求 role=admin。
 *
 * 校验通过后，把 userId/role 写进 request 属性，
 * 后面 LoginUserArgumentResolver 和 ApiAccessLogAspect 都从 request 属性取值。
 *
 * 为什么用 @Order(LOWEST_PRECEDENCE - 100)：项目没有其它安全过滤器，
 * 这个值保证它处于过滤器链里较后的位置，但仍在 DispatcherServlet 之前执行。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class ApiSessionAuthFilter extends OncePerRequestFilter {

    // 日志记录器：审计写库失败时打 warn，不影响主流程。
    private static final Logger log = LoggerFactory.getLogger(ApiSessionAuthFilter.class);
    private static final int MAX_URI = 500;
    private static final int MAX_QUERY = 500;
    private static final int MAX_ERR = 500;

    private final ObjectMapper objectMapper;
    // 拒绝请求也要留审计日志（AOP 到不了过滤器这一层）。
    private final ApiAccessLogMapper apiAccessLogMapper;

    // 框架先问：这个请求要不要进过滤器？不需要就跳过，静态资源/非 API 直接放行。
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 只有 /api/ 开头的请求才需要鉴权；其它路径（如静态资源）放给 Spring MVC 处理。
        return path == null || !path.startsWith("/api/");
    }

    // 真正的鉴权逻辑。按 7 步执行，任何一步不满足就直接写响应返回，不再放行。
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // 步骤1：OPTIONS 是浏览器跨域预检，不含业务数据，直接放行让 CORS 配置处理。
        if ("OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 步骤2：尝试读 Session。没有 Session（getSession(false)）就代表没登录过。
        HttpSession session = request.getSession(false);
        Long userId = readUserId(session);
        String role = readRole(session);

        // 步骤3：白名单接口（广场/登录注册/分享访客）允许匿名进入。
        if (isAnonymousApi(method, path)) {
            if (userId != null) {
                // 已登录用户访问匿名接口时，把身份也放进 request，日志里能记录“是谁”。
                request.setAttribute(FsSessionKeys.REQUEST_USER_ID, userId);
                request.setAttribute(FsSessionKeys.REQUEST_ROLE, role);
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 步骤4：不是白名单且没有登录 → 401。
        if (userId == null) {
            // 先补审计日志，再写 401 JSON。
            auditRejectedRequest(request, method, path, 401, "未登录或会话已过期", null);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话已过期");
            return;
        }

        // 步骤5：已登录，把 userId/role 放进 request 属性，后续组件统一从这里取。
        request.setAttribute(FsSessionKeys.REQUEST_USER_ID, userId);
        request.setAttribute(FsSessionKeys.REQUEST_ROLE, role);

        // 步骤6：管理端路径要求平台角色是 admin，否则 403。
        if (path.startsWith("/api/admin/") && !UserRoles.isAdmin(role)) {
            auditRejectedRequest(request, method, path, 403, "需要管理员权限", userId);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return;
        }

        // 步骤7：所有检查通过，把请求交给后面的过滤器 / DispatcherServlet。
        filterChain.doFilter(request, response);
    }

    // 过滤器层审计：401/403 不会走到 Controller，所以在这里补一条日志。
    private void auditRejectedRequest(HttpServletRequest request, String method, String path,
                                      int httpStatus, String message, Long userId) {
        try {
            // 组装一条和 AOP 同结构的日志；success=false 表示请求没成功。
            ApiAccessLog row = ApiAccessLog.builder()
                    .occurredAt(LocalDateTime.now())
                    .durationMs(0)
                    .httpMethod(truncate(method, 16))
                    .requestUri(truncate(path, MAX_URI))
                    .queryString(truncate(request.getQueryString(), MAX_QUERY))
                    .userId(userId)
                    .clientIp(truncate(request.getRemoteAddr(), 64))
                    .success(false)
                    .httpStatus(httpStatus)
                    .controllerMethod("鉴权拦截(" + httpStatus + ")")
                    .errorMessage(truncate(message, MAX_ERR))
                    .build();
            apiAccessLogMapper.insert(row);
        } catch (Exception e) {
            // 审计本身失败不能反过来影响鉴权结果，只警告。
            log.warn("写入鉴权拦截日志失败: {}", e.getMessage());
        }
    }

    // 超长字符串截断：数据库字段有长度上限，URI/错误信息不能超。
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }

    // 从 Session 读用户 id：Session 为 null 或值不是数字时都视为“未登录”。
    private static Long readUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object o = session.getAttribute(FsSessionKeys.SESSION_USER_ID);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return null;
    }

    // 从 Session 读角色；拿不到就返回 null，交给 isAdmin/白名单逻辑处理。
    private static String readRole(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object o = session.getAttribute(FsSessionKeys.SESSION_ROLE);
        return o instanceof String ? (String) o : null;
    }

    // 判断“这个请求允许匿名访问吗”。
    private boolean isAnonymousApi(String method, String path) {
        // 广场全部匿名：项目列表/成员/浏览/下载/预览。
        if (path.startsWith("/api/fs/gallery")) {
            return true;
        }
        // 只有 POST /login 与 POST /register 匿名；logout 仍要求登录。
        if (path.equals("/api/auth/login") && "POST".equalsIgnoreCase(method)) {
            return true;
        }
        if (path.equals("/api/auth/register") && "POST".equalsIgnoreCase(method)) {
            return true;
        }
        return isPublicShareSubPath(method, path);
    }

    // 分享的访客子路径：只放行 meta/unlock/download/preview-pdf 四个动作，
    // 创建分享 POST /api/share 本身还是要登录。
    private static boolean isPublicShareSubPath(String method, String path) {
        if (!path.startsWith("/api/share/")) {
            return false;
        }
        int i = path.indexOf('/', "/api/share/".length());
        if (i < 0) {
            return false;
        }
        String tail = path.substring(i);
        // 每个访客动作还要校验 HTTP 方法，防止用错误方法访问。
        if (tail.endsWith("/meta")) {
            return "GET".equalsIgnoreCase(method);
        }
        if (tail.endsWith("/unlock")) {
            return "POST".equalsIgnoreCase(method);
        }
        if (tail.endsWith("/download")) {
            return "GET".equalsIgnoreCase(method);
        }
        if (tail.endsWith("/preview-pdf")) {
            return "GET".equalsIgnoreCase(method);
        }
        return false;
    }

    // 写标准 JSON 错误响应，格式固定为 {"error":"..."}。
    private void writeJson(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
