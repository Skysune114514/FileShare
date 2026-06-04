package org.example.fileshare1.share;

// 分享功能相关的“协议常量”集中地：
// Redis 键前缀、访客解锁 Cookie 名、解锁会话有效期。

public final class ShareConstants {

    // 分享链接载荷 key 前缀：fs:share:link:{code}。
    public static final String LINK_KEY_PREFIX = "fs:share:link:";
    // 解锁令牌 key 前缀：fs:share:grant:{token}，value 存分享码。
    public static final String GRANT_KEY_PREFIX = "fs:share:grant:";
    // PIN 校验通过后写入浏览器的 Cookie 名。
    public static final String COOKIE_GRANT = "FS_SHARE_GRANT";
    // 解锁会话 30 分钟：Cookie 过期 = 要重新输 PIN。
    public static final int GRANT_TTL_SECONDS = 1800;

    // 工具类禁止实例化。
    private ShareConstants() {
    }
}
