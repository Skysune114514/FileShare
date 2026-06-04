package org.example.fileshare1.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.dto.CreateShareRequest;
import org.example.fileshare1.dto.ShareCreateResponse;
import org.example.fileshare1.dto.ShareMetaResponse;
import org.example.fileshare1.dto.UnlockShareRequest;
import org.example.fileshare1.security.LoginUser;
import org.example.fileshare1.service.ShareLinkService;
import org.example.fileshare1.share.ShareConstants;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 临时分享接口（/api/share）。
 *
 * 只有 POST /api/share（创建）需要登录；
 * 分享码 meta/unlock/download/preview 对访客开放（Filter 白名单）。
 * PIN 验证成功后，Controller 负责把“解锁令牌”写进 HttpOnly Cookie，
 * 后续 download/preview 请求会由浏览器自动带上这个 Cookie。
 */
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    // 创建分享：登录用户为单个文件生成限时链接。
    @PostMapping
    public ShareCreateResponse create(
            @LoginUser long userId,
            @RequestBody CreateShareRequest body) {
        return shareLinkService.create(userId, body);
    }

    // 访客查看分享元信息：不下载文件也能看到文件名/是否需要 PIN/剩余时间。
    @GetMapping("/{code}/meta")
    public ShareMetaResponse meta(@PathVariable String code) {
        return shareLinkService.meta(code);
    }

    // PIN 解锁：校验成功后把 grant token 写入 Cookie。
    @PostMapping("/{code}/unlock")
    public ResponseEntity<Void> unlock(
            @PathVariable String code,
            @RequestBody(required = false) UnlockShareRequest body,
            HttpServletResponse response) {
        String pin = body != null ? body.getPin() : null;
        // Service 校验 PIN；错误会抛 400，正确才返回临时 token。
        String token = shareLinkService.unlock(code, pin);
        // 构造 HttpOnly Cookie：JS 读不到；Path 限定 /api/share，不污染主站其它接口。
        ResponseCookie cookie = ResponseCookie.from(ShareConstants.COOKIE_GRANT, token)
                .path("/api/share")
                .httpOnly(true)
                .maxAge(ShareConstants.GRANT_TTL_SECONDS)
                .sameSite("Lax")
                .build();
        // 把 Set-Cookie 加到响应头。
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        // 不需要响应体，返回 200 即可。
        return ResponseEntity.ok().build();
    }

    // 访客下载分享文件；Cookie 可能没有（无 PIN 分享或未解锁）。
    @GetMapping("/{code}/download")
    public ResponseEntity<Resource> download(
            @PathVariable String code,
            @CookieValue(value = ShareConstants.COOKIE_GRANT, required = false) String grant) {
        return shareLinkService.download(code, grant);
    }

    // 访客预览分享的 Office 文件。
    @GetMapping("/{code}/preview-pdf")
    public ResponseEntity<Resource> previewOfficePdf(
            @PathVariable String code,
            @CookieValue(value = ShareConstants.COOKIE_GRANT, required = false) String grant) {
        return shareLinkService.previewOfficePdf(code, grant);
    }
}
