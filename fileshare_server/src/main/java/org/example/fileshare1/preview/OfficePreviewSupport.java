package org.example.fileshare1.preview;

// 预览类型判断工具：什么文件能交给 LibreOffice 转 PDF？
// 判断依据双保险：先看 MIME 关键字，再看扩展名集合。

import java.util.Locale;
import java.util.Set;

// 支持转换的扩展名集合（与前端 filePreview.js 的 OFFICE_EXTENSIONS 保持同一份能力）。
public final class OfficePreviewSupport {

    // 支持的扩展名白名单。
    private static final Set<String> EXTENSIONS = Set.of(
            ".doc", ".docx", ".docm", ".dot", ".dotx",
            ".xls", ".xlsx", ".xlsm", ".xlsb",
            ".ppt", ".pptx", ".pptm", ".pps", ".ppsx",
            ".odt", ".ods", ".odp", ".odg", ".odf",
            ".rtf", ".csv"
    );

    private OfficePreviewSupport() {
    }

    // 判断：MIME 命中（msword/wordprocessingml/spreadsheetml/...）或扩展名在白名单内 → 可预览。
    public static boolean isOfficeDocumentForPreview(String fileName, String contentType) {
        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (ct.contains("msword")
                || ct.contains("wordprocessingml")
                || ct.contains("spreadsheetml")
                || ct.contains("excel")
                || ct.contains("presentationml")
                || ct.contains("powerpoint")
                || ct.contains("opendocument.text")
                || ct.contains("opendocument.spreadsheet")
                || ct.contains("opendocument.presentation")
                || ct.contains("opendocument.graphics")
                || ct.contains("rtf")
                || ct.contains("csv")) {
            return true;
        }
        String ext = extensionOf(fileName);
        return ext != null && EXTENSIONS.contains(ext);
    }

    // 从文件名取出小写扩展名；没有点或点在末尾时返回 null。
    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
