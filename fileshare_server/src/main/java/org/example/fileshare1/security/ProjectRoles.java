package org.example.fileshare1.security;

/**
 * 项目内角色常量。
 *
 * 一个用户能同时是：平台普通用户 + 某项目成员/管理员。
 * 项目管理员可以管成员、删项目根、设置广场公开；普通成员只有项目内写权限。
 * 创建者不依赖这行记录：accessInProject 里 owner 恒等于 ADMIN，但启动时也会补一行记录方便列表展示。
 */
public final class ProjectRoles {

    private ProjectRoles() {
    }

    public static final String MEMBER = "member";
    public static final String PROJECT_ADMIN = "project_admin";

    // 项目管理员判断：必须精确等于 project_admin，不允许大小写混用。
    public static boolean isProjectAdmin(String role) {
        return PROJECT_ADMIN.equals(role);
    }
}
