package org.example.fileshare1.util;

import org.example.fileshare1.junit.UnitTest;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipArchiveSupportTest {

    @TempDir
    Path tempDir;

    @UnitTest("[单元] GBK 编码 ZIP 解压后中文路径正确")
    void unzipGbkChinesePaths() throws IOException {
        Path zip = tempDir.resolve("gbk.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), Charset.forName("GBK"))) {
            zos.putNextEntry(new ZipEntry("资料夹/中文文件.txt"));
            zos.write("ok".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Charset detected = ZipArchiveSupport.detectCharset(zip);
        assertTrue("GBK".equals(detected.name()) || "GB18030".equals(detected.name()),
                () -> "expected GBK family, got " + detected.name());

        Path dest = tempDir.resolve("out");
        Files.createDirectories(dest);
        ZipArchiveSupport.unzipToDirectory(zip, dest);

        assertTrue(Files.isRegularFile(dest.resolve("资料夹/中文文件.txt")));
        assertEquals("ok", Files.readString(dest.resolve("资料夹/中文文件.txt")));
    }

    @UnitTest("[单元] UTF-8 编码 ZIP 仍优先识别为 UTF-8")
    void unzipUtf8Paths() throws IOException {
        Path zip = tempDir.resolve("utf8.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("docs/说明.txt"));
            zos.write("utf8".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertEquals(StandardCharsets.UTF_8, ZipArchiveSupport.detectCharset(zip));

        Path dest = tempDir.resolve("out-utf8");
        Files.createDirectories(dest);
        ZipArchiveSupport.unzipToDirectory(zip, dest);
        assertTrue(Files.isRegularFile(dest.resolve("docs/说明.txt")));
    }

    @UnitTest("[安全] ZIP 含 .. 路径条目时拒绝解压")
    void zipPathTraversalRejected() throws IOException {
        Path zip = tempDir.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("../outside.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path dest = tempDir.resolve("evil-out");
        Files.createDirectories(dest);
        assertThrows(IllegalArgumentException.class, () -> ZipArchiveSupport.unzipToDirectory(zip, dest));
        assertFalse(Files.exists(dest.resolveSibling("outside.txt")));
    }
}
