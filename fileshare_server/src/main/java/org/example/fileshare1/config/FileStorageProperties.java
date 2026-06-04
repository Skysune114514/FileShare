package org.example.fileshare1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 文件存储配置类。
 *
 * 项目里「数据库只存文件元数据，真实文件放在磁盘」，而磁盘根目录就是这里配置的。
 * 谁读它？FsNodeServiceImpl 和 ShareLinkServiceImpl。
 * 它们拼文件路径时都会写：storageProperties.getRoot().resolve(storageKey)。
 *
 * 为什么是 @Component：因为希望 Spring 自动创建它，别的类直接构造注入就能用。
 */
@Data
@Component
// 和 yml 里的 file.storage 段绑定。
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageProperties {

    // 物理文件根目录。默认值是相对路径：data/file-storage（相对启动进程的工作目录）。
    // 学习环境 yml 里 file.storage.root 会覆盖这里。
    private Path root = Path.of("data", "file-storage");
}
