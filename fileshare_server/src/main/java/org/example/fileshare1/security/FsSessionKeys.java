package org.example.fileshare1.security;

/**
 * Session / Request 的“键名协议”。
 *
 * 同一个值会在三个地方流转，名字必须全项目统一：
 * 1. AuthController 把登录用户 id/角色写进 HttpSession（Redis）；
 * 2. ApiSessionAuthFilter 从 Session 读出来，再写进本次 request；
 * 3. LoginUserArgumentResolver 与 ApiAccessLogAspect 再从 request 读取。
 *
 * 把键名集中成常量，能避免三处手写字符串不一致。
 */
public final class FsSessionKeys {
    // 会话里存“当前用户 id”的键；对应 AuthController.establishSession。
    public static final String SESSION_USER_ID = "FS_UID";
    // 会话里存“平台角色”的键，取值 user/admin。
    public static final String SESSION_ROLE = "FS_ROLE";

    // 过滤器写入 request 属性的键。用完整类名拼后缀，避免和第三方同名属性冲突。
    public static final String REQUEST_USER_ID = FsSessionKeys.class.getName() + ".userId";
    public static final String REQUEST_ROLE = FsSessionKeys.class.getName() + ".role";

    // 工具类不该被 new，把构造方法私有掉。
    private FsSessionKeys() {
    }
}
