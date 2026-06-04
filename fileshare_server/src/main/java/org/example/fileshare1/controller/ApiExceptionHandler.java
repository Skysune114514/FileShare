package org.example.fileshare1.controller;

import org.example.fileshare1.exception.FileNameConflictException;
import org.example.fileshare1.preview.OfficePreviewFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理（@RestControllerAdvice）。
 *
 * 作用：让 Service 可以放心“想抛就抛”，不用每个 Controller 写 try-catch。
 * Spring MVC 捕获到这些异常后，会按“异常类型”找到下面最匹配的 @ExceptionHandler 方法，
 * 把异常翻译成统一的 JSON 响应。
 *
 * 设计约定：
 * - 业务校验失败抛 IllegalArgumentException（400，用户可理解的消息）；
 * - 服务端状态异常抛 IllegalStateException（500）；
 * - 文件重名抛 FileNameConflictException（409，带 existingFileId 供前端引导替换）；
 * - 数据库异常只给泛化文案，细节进日志。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    // 日志记录器：记录错误详情，但不能把详情直接返回给用户。
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // 409：同名文件冲突。响应不止给一句话，还带 existingFileId/fileName，
    // 前端据此弹“是否替换为对该文件的修改”。
    @ExceptionHandler(FileNameConflictException.class)
    public ResponseEntity<Map<String, Object>> fileNameConflict(FileNameConflictException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", "FILE_NAME_CONFLICT");
        body.put("existingFileId", e.getExistingFileId());
        body.put("fileName", e.getFileName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // 400：请求头缺失。
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "缺少请求头: " + e.getHeaderName()));
    }

    // 422：LibreOffice 转换失败/超时，提示用户下载原件。
    @ExceptionHandler(OfficePreviewFailedException.class)
    public ResponseEntity<Map<String, String>> officePreviewFailed(OfficePreviewFailedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    // 400：最常见的业务错误入口（参数校验、权限不足等）。
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ExceptionMessageTranslator.toUserMessage(e)));
    }

    // 500：服务端状态错误，例如元数据在库但物理文件丢失。
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> serverError(IllegalStateException e) {
        log.warn("服务端状态异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ExceptionMessageTranslator.toUserMessage(e)));
    }

    // 500：文件名/文本编码错误，提示改用 UTF-8 或 ZIP。
    @ExceptionHandler({
            MalformedInputException.class,
            CharacterCodingException.class
    })
    public ResponseEntity<Map<String, String>> charsetError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "文件或文本编码无法解析，请使用 UTF-8 编码或改用 ZIP 上传"));
    }

    // 413：上传超过限制。
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "上传文件超过大小限制"));
    }

    // 400：multipart 上传格式错误或请求体过大。
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> multipartError(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ExceptionMessageTranslator.toUserMessage(e)));
    }

    // 400：请求体 JSON 解析失败。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> bodyNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "请求体格式错误，请检查提交的数据"));
    }

    // 400：缺少必填请求参数。
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "缺少必填参数: " + e.getParameterName()));
    }

    // 400：参数类型不对，例如把 abc 传给 long。
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "参数「" + e.getName() + "」类型不正确"));
    }

    // 405：方法不支持（例如对 GET 接口发 DELETE）。
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "不支持的请求方法: " + e.getMethod()));
    }

    // 500：数据库层异常统一处理。详细堆栈进日志，用户只看到泛化提示。
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataAccess(DataAccessException e) {
        log.error("数据库操作失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "数据库操作失败，请稍后重试"));
    }

    // 兜底：其它没单独处理的异常，统一 500 + 泛化中文。
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> fallback(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ExceptionMessageTranslator.toUserMessage(e)));
    }
}
