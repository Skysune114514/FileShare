package org.example.fileshare1.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 参数解析器：把 @LoginUser long userId 变成“当前登录用户的 id”。
 *
 * 执行时机：Spring MVC 准备调用 Controller 方法之前，会逐个问参数解析器
 * “这个参数你认不认？”认的话就由你去取值。
 *
 * 关键依赖：ApiSessionAuthFilter 必须比它先执行，并把 userId 塞进 request 属性；
 * 如果过滤器没执行/没注入，这里就取不到值，会抛异常提醒“检查过滤器顺序”。
 */
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    // Spring MVC 问：这个参数归你管吗？
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // 同时满足两个条件才接管：参数带 @LoginUser，且类型是基本类型 long。
        // 限定 long 是为了安全——其它类型的参数不应该被误当成用户 id。
        return parameter.hasParameterAnnotation(LoginUser.class)
                && parameter.getParameterType().equals(long.class);
    }

    // Spring MVC 确定“归你管”后，调用这个方法来真正取值。
    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        // 从 Web 请求里拿到原始的 HttpServletRequest（过滤器操作的是它）。
        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        if (req == null) {
            // 理论上 Controller 场景一定有 request；没有说明使用环境不对，直接报错。
            throw new IllegalStateException("无 HttpServletRequest");
        }
        // 读过滤器写入的 userId。注意是 request.setAttribute，不是 Session。
        Object v = req.getAttribute(FsSessionKeys.REQUEST_USER_ID);
        if (!(v instanceof Long)) {
            // 类型不对说明过滤器没写入（例如没走 /api 路径或顺序错误），给出明确提示。
            throw new IllegalStateException("未注入登录用户，请检查过滤器顺序");
        }
        return v;
    }
}
