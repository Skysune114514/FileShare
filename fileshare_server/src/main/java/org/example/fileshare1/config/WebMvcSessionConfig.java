package org.example.fileshare1.config;

import lombok.RequiredArgsConstructor;
import org.example.fileshare1.security.LoginUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC 自定义能力注册处。
 *
 * 本类做的事很小：把 LoginUserArgumentResolver 注册进 Spring MVC 的“参数解析器列表”。
 * 注册之后，Controller 方法里凡是写 @LoginUser long userId 的参数，
 * Spring MVC 就会调用 LoginUserArgumentResolver 去取当前登录用户 id。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcSessionConfig implements WebMvcConfigurer {

    // 参数解析器实例，由 Spring 注入；它本质是个普通 @Component。
    private final LoginUserArgumentResolver loginUserArgumentResolver;

    // Spring MVC 启动时会把框架自带的解析器都放进 resolvers，我们在这里追加自己的。
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        // 追加到列表末尾。它只处理带 @LoginUser 的参数，所以不影响其它参数解析。
        resolvers.add(loginUserArgumentResolver);
    }
}
