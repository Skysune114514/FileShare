package org.example.fileshare1.preview;

// 预览失败专用异常：LibreOffice 转换失败/超时/进程被中断时抛出。

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// @ResponseStatus 让这个异常“自带 HTTP 语义”；不过项目里有全局异常处理器，
// 所以真正映射 422 的是 ApiExceptionHandler.officePreviewFailed。
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class OfficePreviewFailedException extends RuntimeException {

    // 构造时传入给用户看的短消息（例如“文档转换超时，请下载原件查看”）。
    public OfficePreviewFailedException(String message) {
        super(message);
    }
}
