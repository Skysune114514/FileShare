package org.example.fileshare1;

// 引入「自定义 CORS 配置属性」类。
// 作用是告诉代码：yml 里 app.cors.xxx 这些配置可以填进 AppCorsProperties 的对象里。
import org.example.fileshare1.config.AppCorsProperties;

// Spring Boot 的启动器类，作用是提供 run() 方法。
// 你不需要理解它内部实现，只要知道 main 方法调用它就能把整个程序拉起来。
import org.springframework.boot.SpringApplication;

// 「自动配置开关」：Spring Boot 会按依赖自动做很多事。
// 例如：项目里有 spring-boot-starter-web，它就自动配好内嵌 Tomcat；
// 有 mybatis-plus 就自动配好数据源相关组件。这个类本身不用写太多配置。
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 开启「配置属性绑定」功能：把 yml 配置绑定到指定类上。
// 下面用 @EnableConfigurationProperties(AppCorsProperties.class) 告诉 Spring 要绑定哪个类。
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// =====================================================================
// 启动类，也是整个后端的「门面」。
//
// Spring Boot 扫描 Bean 时，会以这个类所在的包（org.example.fileshare1）为根，
// 向下扫描所有子包（config/controller/service/security/aop/...），
// 所以其他业务类都必须放在这个包或其子包下面，否则不会被 Spring 发现。
//
// 特别注意：这里没有写 @MapperScan。
// 因为 WebMvc 测试只加载 Controller，不加载 MyBatis；
// 如果 Mapper 扫描写在这里，测试启动时可能因为找不到 MyBatis 的工厂类而报错。
// 所以 Mapper 扫描被单独放到了 config/MybatisMapperConfig.java。
// =====================================================================

// 这一个注解等于三个注解的合体：
//   1. @Configuration        —— 标记这是一个配置类，Spring 可以读里面的 Bean；
//   2. @EnableAutoConfiguration —— 让 Spring Boot 按依赖自动配置各种组件；
//   3. @ComponentScan        —— 扫描当前包及子包，把带 @Component/@Service/@Controller 的类注册成 Bean。
// 它的调用时机：main 方法里 SpringApplication.run() 启动时，由 Spring 读取这个注解开始装配。
@SpringBootApplication

// 让 AppCorsProperties 这个「普通类」也能从 yml 里读取 app.cors.* 配置。
// 如果不写这一行，Spring 就不会去管理它，前端跨域请求配置就无法生效。
// 注意：FileStorageProperties 等类没有写在这里，因为它们是 @Component，
// 自己就能被 Spring 发现；AppCorsProperties 没有标 @Component，所以要手动在这里点名注册。
@EnableConfigurationProperties(AppCorsProperties.class)
public class FileShare1Application {

    /**
     * Java 程序的唯一入口。
     *
     * main 方法本身不写业务逻辑，它只做两件事：
     *   1. 把「启动类本身」传给 SpringApplication.run(...)；
     *   2. run 方法返回后，内嵌 Tomcat 已经开始监听 9090 端口，程序就常驻运行了。
     *
     * 参数 args：命令行里传入的参数（例如 --server.port=9090）。
     * Spring Boot 会把它和 application.yml 合并，yml 优先级一般更高。
     */
    public static void main(String[] args) {
        // 启动 Spring 容器：
        //   - 先读取 application.yml / application-learn.yml；
        //   - 再执行 @PostConstruct 之类的初始化方法（例如创建存储目录、给老库补列）；
        //   - 最后启动内嵌 Tomcat，等待浏览器/Nginx 请求进来。
        SpringApplication.run(FileShare1Application.class, args);
    }
}
