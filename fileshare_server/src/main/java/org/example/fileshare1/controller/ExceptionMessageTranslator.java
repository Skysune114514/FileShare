package org.example.fileshare1.controller;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;

/**
 * 异常消息翻译器（包内私有工具类）。
 *
 * 处理顺序很有讲究：
 * 1. 异常消息本身是中文 → 直接返回（说明是业务层写好的用户提示）；
 * 2. 不是中文 → 找最底层根因，按异常类型/关键字翻译；
 * 3. 还认不出来 → 返回一句通用中文，绝不把底层英文堆栈给用户。
 */
final class ExceptionMessageTranslator {

    private ExceptionMessageTranslator() {
    }

    // 对外唯一入口。
    static String toUserMessage(Throwable e) {
        if (e == null) {
            // 空异常给最通用的提示。
            return "服务器内部错误";
        }
        if (e.getMessage() != null && looksLikeChinese(e.getMessage())) {
            // 业务层抛的异常消息只要含汉字，就认为已经是“人话”，原样透出。
            return e.getMessage();
        }
        // 剥开多层包装，找到最底层原因（真正的 SQL 报错/IO 报错通常在最底层）。
        Throwable root = rootCause(e);
        if (root instanceof MalformedInputException || root instanceof CharacterCodingException) {
            // ZIP/文件名解码失败。
            return "文件或文本编码无法解析，请使用 UTF-8 编码的文件，或改用 ZIP 上传";
        }
        if (e instanceof MaxUploadSizeExceededException) {
            return "上传文件超过大小限制";
        }
        if (e instanceof MultipartException) {
            return "文件上传格式错误或请求体过大";
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式错误，请检查 JSON 是否正确";
        }
        if (e instanceof MissingServletRequestParameterException m) {
            return "缺少必填参数: " + m.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return "参数类型错误: " + m.getName();
        }
        if (e instanceof HttpRequestMethodNotSupportedException m) {
            return "不支持的请求方法: " + m.getMethod();
        }
        if (e instanceof NoHandlerFoundException) {
            return "接口不存在";
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            // 根因连消息都没有，只能给通用提示。
            return "服务器内部错误，请稍后重试";
        }
        if (msg.contains("Connection refused")) {
            // 最常见的“依赖没启动”类错误，翻译成可操作的提示。
            return "无法连接依赖服务，请检查 Redis 或数据库是否已启动";
        }
        if (msg.contains("MalformedInputException") || msg.contains("malformed input")) {
            return "文件或文本编码无法解析，请使用 UTF-8 或 ZIP 上传";
        }
        // 未识别出的底层异常一律只给泛化文案；细节留在服务端日志
        return "服务器内部错误，请稍后重试";
    }

    // 检测字符串里是否包含汉字；含汉字就当“已经是中文用户提示”。
    private static boolean looksLikeChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    // 沿 cause 链一路往下，直到没有更底层的原因。
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
