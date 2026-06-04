package org.example.fileshare1.aop;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * “英文接口 → 中文操作名”的映射表。
 *
 * 为什么需要它：api_access_log 是给管理员看的，不能显示 FsController.uploadFile，
 * 而要显示“上传文件”。本项目做法是：只维护 OPERATIONS 一份清单，
 * 启动时自动拆成两张可查询的表：按方法名查 + 按 HTTP 方法/URI 查。
 *
 * 两个使用场景：
 * 1. 写入日志时（ApiAccessLogAspect.record）调用 resolveForWrite，得到中文标签并存入数据库；
 * 2. 展示日志时（AdminController.accessLogs）调用 toDisplayLabel，
 *    兼容历史上可能存的旧英文/旧中文格式。
 */
public final class ApiOperationLabels {

    // 一条映射的四个要素：唯一方法键（类.方法）、HTTP 方法、URI 规则、要展示的中文名。
    private record Operation(String methodKey, String httpMethod, String uriPattern, String label) {
    }

    // 全项目接口的中文标签清单。以后新增接口，只需要照格式在这里加一行。
    // uriPattern 以 ^ 开头的是正则，能匹配带数字 id 的动态路径；否则是精确路径。
    private static final Operation[] OPERATIONS = {
            op("AuthController.register", "POST", "/api/auth/register", "用户注册"),
            op("AuthController.login", "POST", "/api/auth/login", "用户登录"),
            op("AuthController.logout", "POST", "/api/auth/logout", "用户登出"),
            op("FsController.list", "GET", "/api/fs/nodes", "列出目录内容"),
            op("FsController.createProject", "POST", "/api/fs/projects", "新建项目"),
            op("FsController.createFolder", "POST", "/api/fs/folders", "新建文件夹"),
            op("FsController.uploadFile", "POST", "/api/fs/files", "上传文件"),
            op("FsController.uploadZip", "POST", "/api/fs/upload-zip", "上传 ZIP 整包"),
            op("FsController.uploadFolder", "POST", "/api/fs/upload-folder", "上传文件夹"),
            op("FsController.replaceFile", "POST", "^/api/fs/nodes/\\d+/replace$", "替换文件内容"),
            op("FsController.listRevisions", "GET", "^/api/fs/nodes/\\d+/revisions$", "查看文件历史版本"),
            op("FsController.downloadRevision", "GET", "^/api/fs/nodes/\\d+/revisions/\\d+/download$", "下载历史版本"),
            op("FsController.previewRevisionOfficePdf", "GET", "^/api/fs/nodes/\\d+/revisions/\\d+/preview-pdf$", "预览历史版本 PDF"),
            op("FsController.deleteRevision", "DELETE", "^/api/fs/nodes/\\d+/revisions/\\d+$", "删除历史版本"),
            op("FsController.delete", "DELETE", "^/api/fs/nodes/\\d+$", "删除节点"),
            op("FsController.renameFolder", "PATCH", "^/api/fs/nodes/\\d+/name$", "重命名文件夹"),
            op("FsController.setPublic", "PUT", "^/api/fs/nodes/\\d+/public$", "设置广场公开"),
            op("FsController.setPublic", "PATCH", "^/api/fs/nodes/\\d+/public$", "设置广场公开"),
            op("FsController.download", "GET", "^/api/fs/nodes/\\d+/download$", "下载文件"),
            op("FsController.previewOfficePdf", "GET", "^/api/fs/nodes/\\d+/preview-pdf$", "预览 Office 转 PDF"),
            op("GalleryController.projects", "GET", "/api/fs/gallery/projects", "广场分页项目"),
            op("GalleryController.listProjectMembers", "GET", "^/api/fs/gallery/projects/\\d+/members$", "广场查看项目成员"),
            op("GalleryController.listNodes", "GET", "^/api/fs/gallery/\\d+/nodes$", "广场浏览目录"),
            op("GalleryController.download", "GET", "^/api/fs/gallery/\\d+/files/\\d+/download$", "广场下载文件"),
            op("GalleryController.previewOfficePdf", "GET", "^/api/fs/gallery/\\d+/files/\\d+/preview-pdf$", "广场预览 PDF"),
            op("ProjectMemberController.myRole", "GET", "^/api/fs/projects/\\d+/my-role$", "查询我在项目中的角色"),
            op("ProjectMemberController.listMembers", "GET", "^/api/fs/projects/\\d+/members$", "列出项目成员"),
            op("ProjectMemberController.candidates", "GET", "^/api/fs/projects/\\d+/member-candidates$", "搜索可邀请成员"),
            op("ProjectMemberController.addMember", "POST", "^/api/fs/projects/\\d+/members$", "添加项目成员"),
            op("ProjectMemberController.removeMember", "DELETE", "^/api/fs/projects/\\d+/members/\\d+$", "移除项目成员"),
            op("ShareLinkController.create", "POST", "/api/share", "创建临时分享"),
            op("ShareLinkController.meta", "GET", "^/api/share/[^/]+/meta$", "查看分享信息"),
            op("ShareLinkController.unlock", "POST", "^/api/share/[^/]+/unlock$", "验证分享 PIN"),
            op("ShareLinkController.download", "GET", "^/api/share/[^/]+/download$", "分享链接下载"),
            op("ShareLinkController.previewOfficePdf", "GET", "^/api/share/[^/]+/preview-pdf$", "分享链接预览 PDF"),
            op("AdminController.unpublishPublicNode", "POST", "^/api/admin/gallery/projects/\\d+/unpublish$", "管理员撤销公开"),
            op("AdminController.accessLogs", "GET", "/api/admin/access-logs", "查询访问日志"),
    };

    // 小工厂方法：让 OPERATIONS 数组写起来更短（op("类.方法", "GET", "/path", "中文")）。
    private static Operation op(String methodKey, String httpMethod, String uriPattern, String label) {
        return new Operation(methodKey, httpMethod, uriPattern, label);
    }

    // 已经删除的旧功能（老版本 UserController/GalleryController 分享者），
    // 数据库里可能还存着这些旧英文键，展示时仍要能翻译。
    private static final Map<String, String> LEGACY_BY_METHOD = Map.of(
            "UserController.list", "查询用户列表",
            "GalleryController.sharers", "广场分享者列表"
    );

    // 启动时构建的查询表：方法键 -> 中文。
    private static final Map<String, String> BY_METHOD;
    // 启动时构建的查询表：URI 规则列表，方法键命中不了就用它。
    private static final UriRule[] URI_RULES;

    // 静态初始化：类第一次被加载时执行一次，把 OPERATIONS 拆成两张表。
    static {
        Map<String, String> byMethod = new HashMap<>();
        java.util.List<UriRule> rules = new java.util.ArrayList<>();
        for (Operation op : OPERATIONS) {
            // 同一行记录分别进两张表，查的时候互为兜底。
            byMethod.put(op.methodKey(), op.label());
            rules.add(new UriRule(op.httpMethod(), toPattern(op.uriPattern()), op.label()));
        }
        BY_METHOD = Map.copyOf(byMethod);
        URI_RULES = rules.toArray(UriRule[]::new);
    }

    // 把配置里写的 URI 规则编译成 Pattern。^ 开头代表正则，否则按普通字符串精确匹配。
    private static Pattern toPattern(String uriPattern) {
        if (uriPattern.startsWith("^")) {
            return Pattern.compile(uriPattern);
        }
        return Pattern.compile(Pattern.quote(uriPattern));
    }

    // 写入日志时调用：优先方法键；方法键查不到再用 URI 规则；再查不到就显示“类·方法”便于排查。
    public static String resolveForWrite(String controllerSimpleName, String methodName, String httpMethod, String uri) {
        String byMethod = BY_METHOD.get(controllerSimpleName + "." + methodName);
        if (byMethod != null) {
            // 方法键命中：例如 FsController.uploadFile -> 上传文件。
            return byMethod;
        }
        String byUri = labelFromUri(httpMethod, uri);
        if (byUri != null) {
            return byUri;
        }
        return controllerSimpleName + "·" + methodName;
    }

    // 展示时调用：处理“库里的值五花八门”的情况，统一转成当前中文名。
    public static String toDisplayLabel(String stored, String httpMethod, String requestUri) {
        if (stored == null || stored.isBlank()) {
            // 空值只能靠 URI 猜。
            return labelFromUri(httpMethod, requestUri) != null ? labelFromUri(httpMethod, requestUri) : "—";
        }
        if (BY_METHOD.containsValue(stored)) {
            // 已经是最新的中文标签，直接原样返回。
            return stored;
        }
        String mapped = BY_METHOD.get(stored);
        if (mapped != null) {
            // 存的是旧英文方法键（例如 FsController.uploadFile），映射成中文。
            return mapped;
        }
        String legacy = LEGACY_BY_METHOD.get(stored);
        if (legacy != null) {
            // 兼容已删除接口的历史数据。
            return legacy;
        }
        String byUri = labelFromUri(httpMethod, requestUri);
        if (byUri != null) {
            return byUri;
        }
        return stored;
    }

    // 纯 URI 匹配：先去掉 query 部分，再和每条规则比较。
    private static String labelFromUri(String httpMethod, String uri) {
        if (httpMethod == null || uri == null || uri.isBlank()) {
            return null;
        }
        String m = httpMethod.trim().toUpperCase();
        String path = uri.split("\\?")[0];
        for (UriRule rule : URI_RULES) {
            // 方法和 URI 都匹配才认为命中。
            if (rule.method.equals(m) && rule.pattern.matcher(path).matches()) {
                return rule.label;
            }
        }
        return null;
    }

    // 编译后的规则：方法 + Pattern + 中文标签。
    private record UriRule(String method, Pattern pattern, String label) {
    }

    // 工具类不可实例化。
    private ApiOperationLabels() {
    }
}
