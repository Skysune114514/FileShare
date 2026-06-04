package org.example.fileshare1.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP 解压工具（静态方法，不保存状态）。
 *
 * 要解决四个问题：
 * 1. 中文乱码：Windows 压缩包常按 GBK/GB18030 编码文件名，需要自动识别；
 * 2. 路径穿越（Zip Slip）：zip 条目如果带 .. 或绝对路径，解压可能写到你不想写的地方；
 * 3. 压缩炸弹：压缩包很小但解压后巨大，必须限制条目数和总字节数；
 * 4. 临时文件泄漏：unzipStreamToDirectory 负责用完删除临时 zip。
 */
public final class ZipArchiveSupport {

    // 尝试识别三种常见编码，按“打分”选择最像的那个。
    private static final List<Charset> CHARSET_CANDIDATES = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK")
    );
    /** 单个 ZIP 允许的最大条目数（含目录条目），防止“百万空文件”类攻击 */
    private static final long MAX_ZIP_ENTRIES = 50_000L;
    /** 单个 ZIP 解压后允许的总字节数；压缩包可达 500MB，解压后必须设独立上限防炸弹 */
    private static final long MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;

    private ZipArchiveSupport() {
    }

    // 解压入口：先识别编码，建一个“预算数组”，再逐条解压。
    public static void unzipToDirectory(Path zipFile, Path destDir) throws IOException {
        Charset charset = detectCharset(zipFile);
        // toRealPath 得到真实绝对路径，后续所有解压目标都必须以它为前缀。
        Path canonicalDest = destDir.toRealPath();
        long[] budget = new long[2]; // [0]=entryCount, [1]=uncompressedBytes
        try (ZipFile zf = new ZipFile(zipFile.toFile(), charset)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // 每个条目都检查一次预算与路径，遇到非法条目立刻中止。
                extractEntry(zf, entry, canonicalDest, budget);
            }
        }
    }

    // 输入流版本：multipart 上传给的是 InputStream，所以先把流落成临时 zip 再走统一逻辑。
    public static void unzipStreamToDirectory(InputStream in, Path destDir) throws IOException {
        Path tempZip = Files.createTempFile("fs-zip-", ".zip");
        try {
            Files.copy(in, tempZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            unzipToDirectory(tempZip, destDir);
        } finally {
            // 无论成功失败都删临时 zip。
            Files.deleteIfExists(tempZip);
        }
    }

    // 编码识别：逐个编码试读，给文件名打分，分数最高者胜。
    static Charset detectCharset(Path zipFile) throws IOException {
        Charset best = StandardCharsets.UTF_8;
        int bestScore = Integer.MIN_VALUE;
        for (Charset cs : CHARSET_CANDIDATES) {
            try {
                int score = scoreEntryNames(zipFile, cs);
                if (score > bestScore) {
                    bestScore = score;
                    best = cs;
                }
            } catch (IOException ignored) {
                // 该编码无法打开此 ZIP，尝试下一个
            }
        }
        return best;
    }

    // 用某种编码读 zip，根据乱码/汉字/控制字符等情况打分。
    private static int scoreEntryNames(Path zipFile, Charset charset) throws IOException {
        int score = 0;
        try (ZipFile zf = new ZipFile(zipFile.toFile(), charset)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name == null || name.isBlank()) {
                    score -= 5;
                    continue;
                }
                if (name.indexOf('\uFFFD') >= 0) {
                    score -= 100;
                }
                if (looksLikeMojibake(name)) {
                    score -= 40;
                }
                long han = name.codePoints()
                        .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                        .count();
                if (han > 0) {
                    score += 15;
                }
                if (name.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\r')) {
                    score -= 30;
                }
                score += 2;
            }
        }
        return score;
    }

    // 乱码启发式：出现大量 Latin-1 扩展区字符且没有汉字时，很可能是“GBK 被当成 UTF-8 读”。
    private static boolean looksLikeMojibake(String name) {
        long latinExtended = name.chars().filter(c -> c >= 0x00C0 && c <= 0x00FF).count();
        long han = name.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        return latinExtended >= 2 && han == 0 && name.chars().anyMatch(c -> c > 127);
    }

    // 解压单个条目：先扣预算，再验证路径安全，最后带预算拷贝。
    private static void extractEntry(ZipFile zf, ZipEntry entry, Path canonicalDest, long[] budget) throws IOException {
        // 条目数上限：防“几万个空文件”拖垮目录树。
        budget[0]++;
        if (budget[0] > MAX_ZIP_ENTRIES) {
            throw new IllegalArgumentException("ZIP 条目数超过上限（" + MAX_ZIP_ENTRIES + "），已中止解压");
        }
        // 用声明大小做一次预检；真正拷贝时还有 copyWithBudget 二次把关。
        long declaredSize = entry.getSize();
        if (declaredSize > 0 && budget[1] + declaredSize > MAX_UNCOMPRESSED_BYTES) {
            throw new IllegalArgumentException("ZIP 解压后体积超过上限（4GB），已中止解压");
        }
        String entryName = entry.getName().replace('\\', '/');
        if (entryName.startsWith("/") || entryName.contains("..")) {
            // 绝对路径或任何含 .. 的条目直接拒绝。
            throw new IllegalArgumentException("非法的 ZIP 路径: " + entry.getName());
        }
        // 拼接后再 normalize，并用 startsWith 校验仍在目标目录内。
        Path target = canonicalDest.resolve(entryName).normalize();
        if (!target.startsWith(canonicalDest)) {
            throw new IllegalArgumentException("非法的 ZIP 路径: " + entry.getName());
        }
        // 目录条目只建目录不写文件。
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return;
        }
        Files.createDirectories(target.getParent());
        // 文件条目：流拷贝时累计真实字节数。
        try (InputStream is = zf.getInputStream(entry);
             OutputStream os = Files.newOutputStream(target)) {
            copyWithBudget(is, os, budget);
        }
    }

    // 预算拷贝：每读一块都累加，超过上限立刻抛错，防止声明大小说谎。
    private static void copyWithBudget(InputStream in, OutputStream out, long[] budget) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            budget[1] += read;
            if (budget[1] > MAX_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException("ZIP 解压后体积超过上限（4GB），已中止解压");
            }
            out.write(buffer, 0, read);
        }
    }

}
