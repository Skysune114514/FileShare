package org.example.fileshare1.service;

// 临时分享业务接口：分享码+载荷存 Redis，可选 PIN 保护。
// 它还会调用 FsNodeService 做两件事：创建时校验读权限、预览时复用 LibreOffice 转换。

import org.example.fileshare1.dto.CreateShareRequest;
import org.example.fileshare1.dto.ShareCreateResponse;
import org.example.fileshare1.dto.ShareMetaResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/**
 * 临时分享业务接口：分享码与载荷存 Redis；可选 PIN；下载/预览与 {@link FsNodeService} 配合。
 * <p>实现类 {@link org.example.fileshare1.service.impl.ShareLinkServiceImpl}。</p>
 */
public interface ShareLinkService {

    ShareCreateResponse create(long userId, CreateShareRequest req);

    ShareMetaResponse meta(String rawCode);

    /** 校验 PIN 后签发短期 grant token，由 Controller 写入 Cookie */
    String unlock(String rawCode, String pin);

    ResponseEntity<Resource> download(String rawCode, String grantCookieValue);

    ResponseEntity<Resource> previewOfficePdf(String rawCode, String grantCookieValue);
}
