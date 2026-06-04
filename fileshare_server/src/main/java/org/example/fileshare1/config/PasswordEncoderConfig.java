package org.example.fileshare1.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置。
 *
 * 全项目只有一个密码编码器 Bean：BCryptPasswordEncoder。
 * 谁注入它？AuthServiceImpl（注册时加密、登录时比对密码）和
 * ShareLinkServiceImpl（分享 PIN 也用它加密/比对）。
 *
 * BCrypt 特点：每次加密自动带随机盐，结果不是固定值，所以不能直接“相等比较”，
 * 必须用 passwordEncoder.matches(明文, 密文)。
 */
@Configuration
public class PasswordEncoderConfig {

    // @Bean：把方法返回值放进 Spring 容器，方法名 passwordEncoder 就是 Bean 名。
    @Bean
    public PasswordEncoder passwordEncoder() {
        // 默认强度 10：加密较慢，但能有效拖慢暴力破解；对登录场景足够用。
        return new BCryptPasswordEncoder();
    }
}
