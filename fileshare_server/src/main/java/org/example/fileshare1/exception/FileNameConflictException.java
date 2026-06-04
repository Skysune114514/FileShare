package org.example.fileshare1.exception;

// 业务异常：上传时发现同目录已有同名文件。
// 它比普通 400 更“结构化”：带着 existingFileId，前端能据此引导用户“替换该文件”。

import lombok.Getter;

/**
 * 同目录下已存在同名文件，前端可提示用户是否改为「替换/修改」该文件。
 */
@Getter
public class FileNameConflictException extends RuntimeException {

    private final long existingFileId;
    private final String fileName;

    public FileNameConflictException(long existingFileId, String fileName) {
        super("同目录下已存在同名文件");
        this.existingFileId = existingFileId;
        this.fileName = fileName;
    }
}
