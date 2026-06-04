package org.example.fileshare1.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置。
 *
 * 它的作用：告诉 MyBatis「去 org.example.fileshare1.mapper 包下找所有 Mapper 接口，
 * 并为每个接口生成可注入的实现 Bean」。
 *
 * 为什么必须单独放一个类，而不是写在启动类上：
 * 写 Controller 单元测试时用 @WebMvcTest，它只加载 Controller 相关 Bean。
 * 如果 @MapperScan 写在启动类上，测试也会扫描 Mapper，
 * 但测试环境又没有 MyBatis 自动配置，于是报「缺少 sqlSessionFactory」。
 * 放在独立配置类里，@WebMvcTest 默认不会加载它，就能避开这个冲突。
 */
@Configuration
// 参数是 Mapper 接口所在的基础包，MyBatis 会扫描它下面的所有接口。
@MapperScan("org.example.fileshare1.mapper")
// 空的配置类即可，功能全部由注解完成。
public class MybatisMapperConfig {
}
