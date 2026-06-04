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
 * 老库升级补丁：为 fs_node 增加 uploaded_by_user_id 列。
 *
 * 为什么需要这一列：
 * 项目内文件的 owner 永远是“项目创建者”，但“这个文件是谁传的”要单独记录。
 * 早期只存了 uploaded_by_name（昵称快照），昵称可以改、可以重名，
 * 用来判断“成员能否删除自己上传的文件”并不可靠，所以补用户 id 快照。
 * 新数据在创建/上传时写入；老数据此列为空，删除时退化为昵称兜底。
 */
@Component
@Profile("!test")
@DependsOn("dataSourceScriptDatabaseInitializer")
@RequiredArgsConstructor
public class FsNodeUploaderColumnPatch {

    private final DataSource dataSource;

    // 启动时执行一次 ALTER。
    @PostConstruct
    public void patch() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE fs_node ADD COLUMN uploaded_by_user_id BIGINT NULL COMMENT '上传者用户 id 快照'");
        } catch (SQLException e) {
            // 1060 = 列已存在：新库脚本已包含该列，直接跳过。
            if (e.getErrorCode() == 1060) {
                return;
            }
            throw new IllegalStateException("无法为 fs_node 添加 uploaded_by_user_id: " + e.getMessage(), e);
        }
    }
}
