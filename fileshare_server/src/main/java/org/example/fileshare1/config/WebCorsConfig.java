package org.example.fileshare1.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS（跨域）配置。
 *
 * 场景：开发时前端跑在 5173，后端跑在 9090，属于两个来源；
 * 前端又要带 Cookie（withCredentials），所以服务端必须明确允许哪些前端来源。
 * 生产时虽然 Nginx 反代成了同源，但保留这套配置也不影响。
 *
 * 实现方式：实现 WebMvcConfigurer 接口，Spring MVC 启动时调用 addCorsMappings 注册规则。
 * 项目没有引入 Spring Security，所以不需要 CorsConfigurationSource Bean。
 */
@Configuration
// 用 Lombok 根据 final 字段生成构造方法：appCorsProperties 由 Spring 自动注入。
@RequiredArgsConstructor
public class WebCorsConfig implements WebMvcConfigurer {

    // 从 yml 读到的允许来源列表。
    private final AppCorsProperties appCorsProperties;

    // Spring MVC 初始化时自动调用；registry 就是“跨域规则登记本”。
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 先取配置的来源列表；没配置就直接不注册跨域规则。
        List<String> patterns = appCorsProperties.getAllowedOriginPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        // List 转数组，注册方法接收可变参数。
        String[] arr = patterns.toArray(String[]::new);
        // 对 /api/** 开头的接口生效。
        registry.addMapping("/api/**")
                // 允许这些“来源模式”访问。
                .allowedOriginPatterns(arr)
                // 允许的 HTTP 方法。
                .allowedMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // 请求头全放行，便于带 Content-Type、Cookie 等。
                .allowedHeaders("*")
                // 允许携带凭据（Cookie）。注意这里不能用 allowAllOrigins + * 组合。
                .allowCredentials(true)
                // 预检请求结果缓存 1 小时，减少浏览器重复发 OPTIONS。
                .maxAge(3600);
    }
}
