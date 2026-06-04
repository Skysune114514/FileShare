package org.example.fileshare1.aop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.fileshare1.entity.ApiAccessLog;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.example.fileshare1.security.FsSessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * AOP 访问日志切面。
 *
 * 功能一句话：每次 Controller 方法被调用时，自动在旁边计时、记录成败、写进 api_access_log。
 *
 * 为什么用 AOP 而不是在 Controller 里手写：
 * 日志是“横切关注点”——每个接口都需要，但不能让每个 Controller 都复制一遍日志代码。
 * 切面用“切点表达式”声明：controller 包下所有 public 方法都归我管，
 * 但 ApiExceptionHandler（全局异常处理器）不算业务接口，排除掉。
 *
 * 两个容易记混的点：
 * 1. 日志写在 finally：成功、抛异常都会写，保证审计完整；
 * 2. 日志写库失败只 warn：日志是旁路，不能因为日志把业务请求搞挂。
 */
@Aspect
@Component
// Order(50)：同一方法上有多个切面时决定执行顺序。这里没有其它业务切面，主要是明确记录。
@Order(50)
@RequiredArgsConstructor
public class ApiAccessLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessLogAspect.class);

    // 数据库字段长度有限，入库前先截断。
    private static final int MAX_URI = 500;
    private static final int MAX_QUERY = 500;
    private static final int MAX_ERR = 500;
    private static final int MAX_METHOD = 380;

    private final ApiAccessLogMapper apiAccessLogMapper;

    // 环绕通知：proceed() 之前是“前置”，之后是“后置”，异常也会被抓住。
    @Around("execution(public * org.example.fileshare1.controller..*(..)) "
            + "&& !within(org.example.fileshare1.controller.ApiExceptionHandler)")
    public Object aroundController(ProceedingJoinPoint pjp) throws Throwable {
        // 计时起点：nanoTime 精度高，适合算接口耗时。
        long startNs = System.nanoTime();
        Throwable thrown = null;
        Object result = null;
        try {
            // 真正调用 Controller 方法。参数解析失败、Service 抛错都会从这里冒出来。
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            // 先记住异常（供 finally 判断成败），然后原样抛出，别吞掉业务异常。
            thrown = t;
            throw t;
        } finally {
            // 无论成功失败都会走到这里。耗时最少算 0 毫秒。
            long durationMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            try {
                record(pjp, durationMs, result, thrown);
            } catch (Exception e) {
                // 日志落库失败只警告，不影响已经完成的业务结果。
                log.warn("[消息] 写入 api_access_log 失败: {}", e.getMessage());
            }
        }
    }

    // 把一次 Controller 调用组装成一行日志。整段可分成“取信息→转中文→落库”三块。
    private void record(ProceedingJoinPoint pjp, long durationMs, Object result, Throwable thrown) {
        // 从 Spring 的请求上下文里拿当前 HttpServletRequest。
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // 没有请求上下文（例如测试中直接调 service）就不记，避免空指针。
            return;
        }
        HttpServletRequest req = attrs.getRequest();
        // 方法签名能告诉我们“哪个 Controller 的哪个方法”被调用了。
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String controllerSimple = sig.getDeclaringType().getSimpleName();
        String javaMethod = sig.getName();
        String method = nullToEmpty(req.getMethod());
        String uri = truncate(nullToEmpty(req.getRequestURI()), MAX_URI);
        // 把类名.方法名交给映射表，转成中文操作名；比如 FsController.uploadFile -> 上传文件。
        String operationLabel = ApiOperationLabels.resolveForWrite(controllerSimple, javaMethod, method, uri);
        String controllerMethod = truncate(operationLabel, MAX_METHOD);
        String query = req.getQueryString();
        if (query != null) {
            query = truncate(query, MAX_QUERY);
        }
        // userId 来自过滤器写入的 request 属性；匿名接口读不到就是 null。
        Long userId = readRequestUserId(req);
        String clientIp = Optional.ofNullable(req.getRemoteAddr()).map(s -> truncate(s, 64)).orElse(null);

        // 成败判定：没有异常就是成功。
        boolean success = thrown == null;
        Integer httpStatus = null;
        if (success && result instanceof ResponseEntity<?> re) {
            // 返回 ResponseEntity 的接口能拿到真实 HTTP 状态码；普通对象没有状态码概念。
            httpStatus = re.getStatusCode().value();
        }

        String errMsg = null;
        if (!success && thrown != null) {
            errMsg = truncate(nullToEmpty(thrown.getMessage()), MAX_ERR);
        }

        // 控制台日志：开发时方便，生产可交给 logback 落到文件。
        if (success) {
            log.info(
                    "[消息] {} {} 耗时{}ms 用户={} 状态={} 操作={}",
                    method,
                    uri,
                    durationMs,
                    userId == null ? "-" : userId,
                    httpStatus == null ? "-" : httpStatus,
                    controllerMethod);
        } else {
            log.warn(
                    "[消息] {} {} 耗时{}ms 用户={} 失败 操作={} 原因={}",
                    method,
                    uri,
                    durationMs,
                    userId == null ? "-" : userId,
                    controllerMethod,
                    errMsg);
        }

        // 组装实体并 insert。注意这里 insert 异常会被上面的调用者 catch 住。
        ApiAccessLog row = ApiAccessLog.builder()
                .occurredAt(LocalDateTime.now())
                .durationMs((int) Math.min(durationMs, Integer.MAX_VALUE))
                .httpMethod(method)
                .requestUri(uri)
                .queryString(query)
                .userId(userId)
                .clientIp(clientIp)
                .success(success)
                .httpStatus(httpStatus)
                .controllerMethod(controllerMethod)
                .errorMessage(errMsg)
                .build();

        apiAccessLogMapper.insert(row);
    }

    // 从 request 属性读用户 id，兼容 Long/Number 两种类型。
    private static Long readRequestUserId(HttpServletRequest req) {
        Object o = req.getAttribute(FsSessionKeys.REQUEST_USER_ID);
        if (o instanceof Long) {
            return (Long) o;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return null;
    }

    // null 安全：null 转成空字符串。
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // 超过 max 就截断并加省略号。
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
