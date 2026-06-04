package org.example.fileshare1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * LibreOffice 预览配置类。
 *
 * 项目不预先把 Office 转成 PDF，而是“用户点预览时才转”。
 * 转一次就把 PDF 缓存到磁盘，下次直接用。本类存放转换相关的开关和限制。
 * 谁读它？LibreOfficePdfConverter（启动 soffice）、FsNodeServiceImpl（转换前检查 50MB 上限）。
 */
@Data
@Component
// 与 yml 的 file.preview.libreoffice 段绑定。
@ConfigurationProperties(prefix = "file.preview.libreoffice")
public class LibreOfficePreviewProperties {

    // 是否允许预览转换。测试环境设为 false，避免测试真的去启动 LibreOffice。
    private boolean enabled = true;

    // soffice 可执行文件路径。Windows 下通常是 soffice.exe 或 soffice.bin。
    private Path sofficePath = Path.of("D:/LibreOffice/program/soffice.bin");

    // 允许转换的源文件上限：52_428_800 = 50MB。
    // 超过就让用户下载原件看，避免把超大文档塞给子进程拖垮服务器。
    private long maxSourceBytes = 52_428_800L;

    // 转换超时时间。超过 120 秒就把 soffice 子进程强制杀掉，防止进程越积越多。
    private int timeoutSeconds = 120;
}
