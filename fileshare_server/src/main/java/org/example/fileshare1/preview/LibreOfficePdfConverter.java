package org.example.fileshare1.preview;

// Office → PDF 转换器：通过命令行调用本机 LibreOffice 的“无头模式”完成转换。
// 调用方是 FsNodeServiceImpl（它负责缓存和锁），本类只负责“把一次转换跑成功”。

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fileshare1.config.LibreOfficePreviewProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 转换职责单一的小组件：
 * 输入一个源文件路径，输出一个 PDF 文件；失败统一抛 OfficePreviewFailedException（HTTP 422）。
 * 整个过程在独立临时目录进行，避免污染源文件；结束后清理临时目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibreOfficePdfConverter {

    // yml 里 file.preview.libreoffice 段：开关、soffice 路径、50MB 上限、120 秒超时。
    private final LibreOfficePreviewProperties props;

    /**
     * 将 {@code sourceFile} 转为 PDF，写入 {@code targetPdf}（父目录须可创建）。
     * 等价于命令行：soffice --headless --convert-to pdf --outdir temp source.ext
     */
    public void convertToPdf(Path sourceFile, Path targetPdf) {
        // 步骤1：检查预览开关与 soffice 可执行文件
        if (!props.isEnabled()) {
            throw new IllegalArgumentException("LibreOffice 预览未启用");
        }
        Path soffice = props.getSofficePath();
        if (soffice == null || !Files.isRegularFile(soffice)) {
            throw new IllegalStateException("未找到 LibreOffice 可执行文件: " + soffice);
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("fs-lo-");
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败: " + e.getMessage(), e);
        }

        try {
            // 步骤2：复制源文件到临时目录并保留扩展名（LO 靠后缀识别格式）
            String ext = OfficePreviewSupport.extensionOf(sourceFile.getFileName().toString());
            if (ext == null || ext.isEmpty()) {
                ext = ".bin";
            }
            Path workInput = tempDir.resolve("source" + ext);
            Files.copy(sourceFile, workInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 步骤3：ProcessBuilder 启动无界面 LibreOffice 子进程
            List<String> cmd = new ArrayList<>();
            cmd.add(soffice.toAbsolutePath().toString());
            cmd.add("--headless");
            cmd.add("--nologo");
            cmd.add("--nofirststartwizard");
            cmd.add("--convert-to");
            cmd.add("pdf");
            cmd.add("--outdir");
            cmd.add(tempDir.toAbsolutePath().toString());
            cmd.add(workInput.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // 关键：输出重定向到临时文件，而不是在主线程 readAllBytes 阻塞等待进程退出。
            // 否则 soffice 卡死但不关闭输出流时，后面的 waitFor(timeout) 根本不会执行。
            Path logFile = tempDir.resolve("soffice.log");
            pb.redirectOutput(logFile.toFile());
            Process process = pb.start();

            // 步骤4：等待进程结束，超时强杀
            boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("LibreOffice 转换超时, cmd={}", cmd);
                throw new OfficePreviewFailedException("文档转换超时，请下载原件查看");
            }
            int code = process.exitValue();
            String output = readProcessLog(logFile);
            Path produced = tempDir.resolve("source.pdf");
            // 步骤5：校验 exit=0 且 source.pdf 非空
            if (code != 0 || !Files.isRegularFile(produced) || Files.size(produced) == 0) {
                log.warn("LibreOffice 转换失败 exit={}, out={}", code, trimLog(output));
                throw new OfficePreviewFailedException("文档转换失败，请下载原件查看");
            }

            // 步骤6：复制到长期预览缓存路径 preview-lo/...
            Files.createDirectories(targetPdf.getParent());
            Files.copy(produced, targetPdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (OfficePreviewFailedException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OfficePreviewFailedException("文档转换被中断，请下载原件查看");
        } catch (IOException e) {
            log.warn("LibreOffice IO 失败", e);
            throw new OfficePreviewFailedException("文档转换失败，请下载原件查看");
        } finally {
            // 步骤7：清理临时目录
            deleteDirQuietly(tempDir);
        }
    }

    /** Windows 下 soffice 控制台输出常为 GBK，避免按 UTF-8 硬解导致乱码或编码异常。 */
    private static String decodeProcessOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new String(bytes, Charset.forName("GBK"));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 转换结束后读取 soffice 输出日志；读失败不影响主流程判定。 */
    private static String readProcessLog(Path logFile) {
        try {
            if (logFile != null && Files.isRegularFile(logFile)) {
                return decodeProcessOutput(Files.readAllBytes(logFile));
            }
            return "";
        } catch (IOException e) {
            return "(无法读取进程输出)";
        }
    }

    private static String trimLog(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 2000 ? t.substring(0, 2000) + "…" : t;
    }

    private static void deleteDirQuietly(Path dir) {
        try {
            if (dir != null && Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }
}
