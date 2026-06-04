package org.example.fileshare1.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 老库升级补丁：给已经存在的 user 表补 password_hash 列。
 *
 * 背景：schema/user.sql 只对“新表”有效（CREATE TABLE IF NOT EXISTS），
 * 老库里表早就建好了，不会自动多出新列，所以需要启动时补一次 ALTER。
 *
 * 三个注解各管一件事：
 * - @Component：让 Spring 启动时创建并执行这个类；
 * - @Profile("!test")：测试用 H2 建新表，不需要这种 MySQL 补丁；
 * - @DependsOn("dataSourceScriptDatabaseInitializer")：必须等 spring.sql.init 建表完成后再 ALTER，
 *   否则表都还不存在，ALTER 会直接报错。
 */
@Component
@Profile("!test")
@DependsOn("dataSourceScriptDatabaseInitializer")
@RequiredArgsConstructor
public class UserPasswordColumnPatch {

    // 数据源连接：拿到连接后才能执行 SQL。
    private final DataSource dataSource;

    // Spring Bean 初始化完成后自动执行，等价于“启动时执行一次”。
    @PostConstruct
    public void patch() {
        // 用 try-with-resources：连接和语句用完自动关闭，防止连接泄漏。
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            // 执行 ALTER：给 user 表加 password_hash 列，允许为空以兼容历史用户。
            st.execute("ALTER TABLE `user` ADD COLUMN password_hash VARCHAR(255) NULL COMMENT 'BCrypt'");
        } catch (SQLException e) {
            // 1060 = MySQL 的“列已存在”。新库建表时已带这列，ALTER 会撞 1060，属正常情况，静默返回。
            if (e.getErrorCode() == 1060) {
                return;
            }
            // 其它错误说明补丁真的失败了，把原因抛出去让启动失败，避免带病运行。
            throw new IllegalStateException("无法为 user 表添加 password_hash: " + e.getMessage(), e);
        }
    }
}
