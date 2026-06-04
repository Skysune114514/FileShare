package org.example.fileshare1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 白名单配置类。
 *
 * 它本身不做事，只负责「接住 yml 里 app.cors.* 的值」。
 * 谁读它？WebCorsConfig。WebCorsConfig 会把这些允许来源注册给 Spring MVC，
 * 浏览器跨域请求时，服务端才知道「哪个前端地址可以带 Cookie 访问我的接口」。
 *
 * 为什么允许 localhost:* 这种带 * 的写法：
 * 因为前端用 withCredentials（带 Cookie）时，响应里不能简单写 *，
 * 必须是“具体来源或模式”，localhost:* 就是模式，能同时匹配 5173/80 等端口。
 */
@Data
// prefix = "app.cors"：告诉 Spring「去 application*.yml 找 app.cors 开头的那段配置」。
// 字段名 allowedOriginPatterns 会自动对应 yml 里的 allowed-origin-patterns（下划线/短横线都能匹配）。
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {
    // 允许跨域访问的来源列表。默认给本机开发常用的两个地址，真实配置写在 yml 里覆盖这里。
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*"
    ));
}
