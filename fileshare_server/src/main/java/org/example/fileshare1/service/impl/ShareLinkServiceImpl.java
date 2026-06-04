package org.example.fileshare1.service.impl;

// 分享业务实现。整个类的数据来源是 Redis：分享载荷和 PIN 解锁令牌都不落 MySQL。
// “能分享谁的文件”会委托给 FsNodeService 判权限，“Office 预览”也委托它转 PDF。

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.config.FileStorageProperties;
import org.example.fileshare1.dto.CreateShareRequest;
import org.example.fileshare1.dto.ShareCreateResponse;
import org.example.fileshare1.dto.ShareMetaResponse;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.mapper.FsNodeMapper;
import org.example.fileshare1.service.FsNodeService;
import org.example.fileshare1.service.ShareLinkService;
import org.example.fileshare1.share.ShareConstants;
import org.example.fileshare1.share.ShareRedisPayload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 临时分享业务：分享码与载荷存 Redis；可选 PIN；下载/预览与 {@link FsNodeService} 读权限校验配合。
 * 由 {@link org.example.fileshare1.controller.ShareLinkController} 暴露 HTTP。
 */
@Service
@RequiredArgsConstructor
public class ShareLinkServiceImpl implements ShareLinkService {

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 10;
    private static final int MAX_CREATE_ATTEMPTS = 8;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final FsNodeMapper fsNodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageProperties storageProperties;
    private final FsNodeService fsNodeService;
    // 安全随机数：生成分享码用，不能用普通 Random（可预测）。
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建分享：校验单文件与读权限 → 解析 TTL/PIN → 载荷写 Redis（随机码 SETNX）。
     */
    @Override
    public ShareCreateResponse create(long userId, CreateShareRequest req) {
        // 第一步校验：没有 nodeId 直接拒绝。
        if (req == null || req.getNodeId() == null) {
            throw new IllegalArgumentException("请提供 nodeId");
        }
        FsNode node = fsNodeMapper.selectById(req.getNodeId());
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("仅支持分享单文件");
        }
        // 权限校验：至少是项目成员（有读权限）才能分享；不是文件 owner 也能分享项目文件。
        fsNodeService.requireReadableNode(userId, node);
        if (node.getStorageKey() == null) {
            throw new IllegalStateException("文件存储路径缺失");
        }
        Path physical = storageProperties.getRoot().resolve(node.getStorageKey());
        if (!Files.isRegularFile(physical)) {
            throw new IllegalStateException("物理文件缺失");
        }

        // 解析有效期：没传默认 24 小时。
        int ttlSeconds = resolveTtlSeconds(req.getTtl());
        String pinPlain = req.getPin() == null ? null : req.getPin().trim();
        String pinHash = null;
        if (pinPlain != null && !pinPlain.isEmpty()) {
            if (pinPlain.length() < 4 || pinPlain.length() > 12) {
                throw new IllegalArgumentException("PIN 长度须为 4～12 位");
            }
            if (!pinPlain.matches("^[0-9A-Za-z]+$")) {
                throw new IllegalArgumentException("PIN 仅允许数字与字母");
            }
            pinHash = passwordEncoder.encode(pinPlain);
        }

        // 组装“分享快照”：文件名/MIME 在创建时定格，下载时还会用 owner/fileId 再核一次。
        ShareRedisPayload payload = ShareRedisPayload.builder()
                .fileId(node.getId())
                .ownerUserId(node.getOwnerUserId())
                .fileName(node.getName())
                .contentType(node.getContentType())
                .pinHash(pinHash)
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化分享数据失败", e);
        }

        // SETNX 原子占位：同一分享码同时只能被一个人创建成功。
        String code = persistWithUniqueCode(json, ttlSeconds);
        return ShareCreateResponse.builder()
                .code(code)
                .path("/s/" + code)
                .ttlSeconds(ttlSeconds)
                .build();
    }

    // 访客查看元信息：文件不落盘下载，只读 Redis。
    @Override
    public ShareMetaResponse meta(String rawCode) {
        String code = normalizeCode(rawCode);
        ShareRedisPayload p = loadPayload(code);
        Long expire = stringRedisTemplate.getExpire(linkKey(code), TimeUnit.SECONDS);
        long ttl = expire == null || expire < 0 ? 0 : expire;
        return ShareMetaResponse.builder()
                .fileName(p.getFileName())
                .contentType(p.getContentType() != null ? p.getContentType() : "")
                .requiresPin(p.getPinHash() != null && !p.getPinHash().isBlank())
                .ttlSecondsRemaining(ttl)
                .build();
    }

    /**
     * 解锁分享：校验 PIN → 签发 grant token 写入 Redis（约 30 分钟）。
     */
    @Override
    public String unlock(String rawCode, String pin) {
        // 先确认分享存在且没过期。
        String code = normalizeCode(rawCode);
        ShareRedisPayload p = loadPayload(code);
        if (p.getPinHash() == null || p.getPinHash().isBlank()) {
            throw new IllegalArgumentException("当前分享未设置 PIN");
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("请输入 PIN");
        }
        // PIN 同样存 BCrypt，所以用 matches 而不是 equals。
        if (!passwordEncoder.matches(pin.trim(), p.getPinHash())) {
            throw new IllegalArgumentException("PIN 错误");
        }
        // 解锁令牌与“哪个分享码”绑定，30 分钟后自动失效。
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                grantKey(token),
                code,
                Duration.ofSeconds(ShareConstants.GRANT_TTL_SECONDS));
        return token;
    }

    // 下载：鉴权通过后读取当前 fs_node，确认文件还在、owner 没变，再从磁盘返回。
    @Override
    public ResponseEntity<Resource> download(String rawCode, String grantCookieValue) {
        String code = normalizeCode(rawCode);
        ShareRedisPayload p = loadPayload(code);
        assertGrantIfNeeded(p, code, grantCookieValue);

        FsNode node = fsNodeMapper.selectById(p.getFileId());
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalStateException("分享的文件已不存在");
        }
        if (!Objects.equals(node.getOwnerUserId(), p.getOwnerUserId())) {
            throw new IllegalStateException("分享的文件已不存在");
        }
        Path path = storageProperties.getRoot().resolve(node.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("物理文件缺失");
        }

        Resource resource = new FileSystemResource(path);
        String mime = node.getContentType() != null ? node.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(node.getName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(mime))
                .body(resource);
    }

    // 预览：和下载共享同一套鉴权，真正转 PDF 交给 FsNodeService。
    @Override
    public ResponseEntity<Resource> previewOfficePdf(String rawCode, String grantCookieValue) {
        String code = normalizeCode(rawCode);
        ShareRedisPayload p = loadPayload(code);
        assertGrantIfNeeded(p, code, grantCookieValue);

        FsNode node = fsNodeMapper.selectById(p.getFileId());
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalStateException("分享的文件已不存在");
        }
        if (!Objects.equals(node.getOwnerUserId(), p.getOwnerUserId())) {
            throw new IllegalStateException("分享的文件已不存在");
        }
        return fsNodeService.previewOfficeAsPdfForSharedFile(node);
    }

    // 有 PIN 的分享必须出示解锁 Cookie，且 Cookie 对应的分享码要和当前 URL 一致。
    private void assertGrantIfNeeded(ShareRedisPayload p, String code, String grantCookieValue) {
        if (p.getPinHash() == null || p.getPinHash().isBlank()) {
            return;
        }
        if (grantCookieValue == null || grantCookieValue.isBlank()) {
            throw new IllegalArgumentException("请先验证 PIN");
        }
        String storedCode = stringRedisTemplate.opsForValue().get(grantKey(grantCookieValue.trim()));
        if (storedCode == null || !normalizeCode(storedCode).equals(code)) {
            throw new IllegalArgumentException("分享会话无效或已过期，请重新验证 PIN");
        }
    }

    // 读载荷：key 不存在/JSON 损坏都给出用户可理解的消息。
    private ShareRedisPayload loadPayload(String code) {
        String json = stringRedisTemplate.opsForValue().get(linkKey(code));
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("分享不存在或已过期");
        }
        try {
            return objectMapper.readValue(json, ShareRedisPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分享数据损坏", e);
        }
    }

    // 生成唯一分享码：最多试 8 次，每次都用 SETNX 原子占位。
    private String persistWithUniqueCode(String json, int ttlSeconds) {
        for (int i = 0; i < MAX_CREATE_ATTEMPTS; i++) {
            String code = randomCode();
            String key = linkKey(code);
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, json, Duration.ofSeconds(ttlSeconds));
            if (Boolean.TRUE.equals(ok)) {
                return code;
            }
        }
        throw new IllegalStateException("生成分享码失败，请重试");
    }

    private static String linkKey(String code) {
        return ShareConstants.LINK_KEY_PREFIX + code;
    }

    private static String grantKey(String token) {
        return ShareConstants.GRANT_KEY_PREFIX + token;
    }

    // 分享码统一大写，这样 URL 里的大小写不会导致查不到。
    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("分享码无效");
        }
        return raw.trim().toUpperCase();
    }

    // 从 32 个字符（去掉了 0/1/I/O，避免阅读混淆）里随机取 10 位。
    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    // TTL 枚举转秒数；只认三种值，其它输入直接报错。
    private static int resolveTtlSeconds(String ttl) {
        if (ttl == null || ttl.isBlank()) {
            return 86400;
        }
        return switch (ttl.trim().toLowerCase()) {
            case "3h" -> 10800;
            case "72h" -> 259200;
            case "24h" -> 86400;
            default -> throw new IllegalArgumentException("ttl 仅支持 3h、24h、72h");
        };
    }
}
