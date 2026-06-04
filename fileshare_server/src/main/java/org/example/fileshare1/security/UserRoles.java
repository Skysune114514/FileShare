package org.example.fileshare1.security;

/**
 * 平台角色常量（与 user 表 role 列的值保持一致）。
 *
 * 平台角色只分两种：普通用户 user、管理员 admin。
 * 它和“项目内角色”是两套体系：
 * 项目内角色存 project_member 表，平台角色存 user 表，互不影响。
 */
public final class UserRoles {
    public static final String USER = "user";
    public static final String ADMIN = "admin";

    private UserRoles() {
    }

    // 判断是否是管理员。用 equalsIgnoreCase 兼容历史数据里可能的大小写不一致。
    public static boolean isAdmin(String role) {
        return ADMIN.equalsIgnoreCase(role);
    }
}
