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
 * 老库升级补丁：为 fs_node 增加 is_public 列。
 *
 * 作用背景与 UserPasswordColumnPatch 相同：
 * 建表脚本只管新表，老表要由这个类在启动时 ALTER 补列。
 * is_public 决定文件/文件夹是否在广场对陌生人可见。
 */
@Component
@Profile("!test")
@DependsOn("dataSourceScriptDatabaseInitializer")
@RequiredArgsConstructor
public class FsNodePublicColumnPatch {

    private final DataSource dataSource;

    // 启动时执行一次。
    @PostConstruct
    public void patch() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            // 老库没有公开标记列，补上后默认私有（0）。
            st.execute("ALTER TABLE fs_node ADD COLUMN is_public TINYINT(1) NOT NULL DEFAULT 0 COMMENT '广场公开'");
        } catch (SQLException e) {
            // 1060 表示列已存在（新库建表已带），忽略即可。
            if (e.getErrorCode() == 1060) {
                return;
            }
            throw new IllegalStateException("无法为 fs_node 添加 is_public: " + e.getMessage(), e);
        }
    }
}
