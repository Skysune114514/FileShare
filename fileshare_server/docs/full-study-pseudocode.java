/*
 * FileShare1 全项目结构伪代码（后端 + 前端）
 * 格式与 chapter4-pseudocode-samples.java 一致：非可运行代码，供学习/论文排版。
 * 每节标注真实源码路径；方法体内以「步骤N」标注逻辑顺序。
 *
 * ========== 目录索引 ==========
 * 一、架构总览
 * 二、后端 Controller 层（8 类，37+ 端点）
 * 三、后端 Service 层（Auth / FsNode / ShareLink）
 * 四、后端 FsNodeServiceImpl 私有辅助方法
 * 五、安全 / AOP / 配置 / 预览 / 工具
 * 六、Entity / DTO 数据形状
 * 七、前端 API / 路由 / 工具
 * 八、前端 Views（8 页）
 * 九、前端 Components / Layouts / 入口
 */

// =============================================================================
// 一、架构总览
// =============================================================================
//
// 浏览器
//   ├─ 开发：Vite :5173  proxy /api → Spring Boot :9090
//   └─ 生产：Nginx :80  静态 dist + /api/ 反代 9090
//        ↓
// ApiSessionAuthFilter（Session 鉴权 + 白名单）
//        ↓
// ApiAccessLogAspect（AOP 环绕 Controller 写 api_access_log）
//        ↓
// Controller → Service → Mapper(MyBatis) / Redis / 磁盘 / LibreOffice 子进程
//
// 前端：Vue3 + Vue Router；无 Pinia；登录态 localStorage + Cookie Session 双轨
// =============================================================================


// =============================================================================
// 二、后端 Controller 层
// =============================================================================

// ========== 2.1 AuthController ==========
// 源文件: controller/AuthController.java  前缀 /api/auth

// POST /api/auth/register
public AuthUserVO register(RegisterRequest body, HttpServletRequest request) {
    // 步骤1：AuthService 校验并写 user 表
    AuthUserVO vo = authService.register(body);
    // 步骤2：写入 Spring Session（Redis）FS_UID + FS_ROLE
    establishSession(request, vo);
    // 步骤3：返回用户信息 JSON（不含密码）
    return vo;
}

// POST /api/auth/login — 同 register，业务换 authService.login

// POST /api/auth/logout
public void logout(HttpServletRequest request) {
    // 步骤1：取现有 Session（不创建新的）
    HttpSession s = request.getSession(false);
    // 步骤2：invalidate 销毁服务端会话
    if (s != null) s.invalidate();
}

private static void establishSession(HttpServletRequest request, AuthUserVO vo) {
    // 步骤1：getSession(true) 绑定 Cookie FSSESSION
    HttpSession session = request.getSession(true);
    // 步骤2：写入用户 id
    session.setAttribute(FS_UID, vo.getId());
    // 步骤3：写入平台角色，缺省 user
    session.setAttribute(FS_ROLE, vo.getRole() != null ? vo.getRole() : "user");
}


// ========== 2.2 FsController ==========
// 源文件: controller/FsController.java  前缀 /api/fs
// 模式：@LoginUser long userId 注入当前用户 → 委托 FsNodeService

// GET /api/fs/nodes?parentId=
public List<FsNodeVO> list(@LoginUser long userId, long parentId) {
    return fsNodeService.listChildren(userId, parentId);
}

// POST /api/fs/projects  body:{name}
public FsNodeVO createProject(@LoginUser long userId, Map body) {
    return fsNodeService.createFolder(userId, ROOT_PARENT_ID, body.get("name"));
}

// POST /api/fs/folders  body:{parentId,name}
public FsNodeVO createFolder(@LoginUser long userId, Map body) {
    return fsNodeService.createFolder(userId, parentId, name);
}

// POST /api/fs/files?parentId=  multipart:file
public FsNodeVO uploadFile(@LoginUser long userId, long parentId, MultipartFile file) {
    return fsNodeService.uploadFile(userId, parentId, file);
}

// POST /api/fs/upload-zip?parentId=
public FsNodeVO uploadZip(...) { return fsNodeService.uploadZipFolder(...); }

// POST /api/fs/upload-folder?parentId=&rootName=  multipart:files[],paths[]
public FsNodeVO uploadFolder(...) { return fsNodeService.uploadFolder(...); }

// POST /api/fs/nodes/{id}/replace  multipart:file
public FsNodeVO replaceFile(...) { return fsNodeService.replaceFileContent(...); }

// GET /api/fs/nodes/{id}/revisions
public List<FsNodeRevisionVO> listRevisions(...) { return fsNodeService.listFileRevisions(...); }

// GET /api/fs/nodes/{id}/revisions/{revisionId}/download
public ResponseEntity<Resource> downloadRevision(...) { return fsNodeService.downloadFileRevision(...); }

// GET /api/fs/nodes/{id}/revisions/{revisionId}/preview-pdf
public ResponseEntity<Resource> previewRevisionOfficePdf(...) { return fsNodeService.previewOfficeAsPdfForRevision(...); }

// DELETE /api/fs/nodes/{id}/revisions/{revisionId}
public void deleteRevision(...) { fsNodeService.deleteFileRevision(...); }

// DELETE /api/fs/nodes/{id}
public void delete(...) { fsNodeService.deleteNode(...); }

// PATCH /api/fs/nodes/{id}/name  body:{name}
public FsNodeVO renameFolder(...) { return fsNodeService.renameFolder(...); }

// PUT|PATCH /api/fs/nodes/{id}/public  body:{public:true/false}
public FsNodeVO setPublic(...) { return fsNodeService.setPublicRecursive(...); }

// GET /api/fs/nodes/{id}/download
public ResponseEntity<Resource> download(...) { return fsNodeService.download(...); }

// GET /api/fs/nodes/{id}/preview-pdf
public ResponseEntity<Resource> previewOfficePdf(...) { return fsNodeService.previewOfficeAsPdf(...); }


// ========== 2.3 GalleryController ==========
// 源文件: controller/GalleryController.java  前缀 /api/fs/gallery（匿名白名单）

// GET /api/fs/gallery/projects?page=&size=
public PageResult<GalleryProjectVO> projects(int page, int size) {
    return fsNodeService.listPublicProjects(page, size);
}

// GET /api/fs/gallery/projects/{projectRootId}/members
public List<ProjectMemberVO> listProjectMembers(@PathVariable long projectRootId) {
    return fsNodeService.listPublicProjectMembers(projectRootId);
}

// GET /api/fs/gallery/{ownerId}/nodes?parentId=
public List<FsNodeVO> listNodes(long ownerId, long parentId) {
    return fsNodeService.listPublicChildren(ownerId, parentId);
}

// GET /api/fs/gallery/{ownerId}/files/{fileId}/download
public ResponseEntity<Resource> download(long ownerId, long fileId) {
    return fsNodeService.downloadPublicFile(ownerId, fileId);
}

// GET /api/fs/gallery/{ownerId}/files/{fileId}/preview-pdf
public ResponseEntity<Resource> previewOfficePdf(long ownerId, long fileId) {
    return fsNodeService.previewPublicOfficeAsPdf(ownerId, fileId);
}


// ========== 2.4 ProjectMemberController ==========
// 源文件: controller/ProjectMemberController.java  前缀 /api/fs/projects

// GET /api/fs/projects/{rootId}/my-role
public Map<String,String> myRole(@LoginUser long userId, long rootId) {
    // 步骤1：查角色字符串 project_admin / member / null
    String role = fsNodeService.getMyProjectRole(userId, rootId);
    // 步骤2：包装为 {role: "..."} 返回
    return Map.of("role", role != null ? role : "");
}

// GET /api/fs/projects/{rootId}/members
public List<ProjectMemberVO> listMembers(...) { return fsNodeService.listProjectMembers(...); }

// GET /api/fs/projects/{rootId}/member-candidates?q=
public List<UserCandidateVO> candidates(...) { return fsNodeService.searchProjectMemberCandidates(...); }

// POST /api/fs/projects/{rootId}/members  body:{userId,role}
public void addMember(...) { fsNodeService.addProjectMember(...); }

// DELETE /api/fs/projects/{rootId}/members/{targetUserId}
public void removeMember(...) { fsNodeService.removeProjectMember(...); }


// ========== 2.5 ShareLinkController ==========
// 源文件: controller/ShareLinkController.java  前缀 /api/share

// POST /api/share  body:{nodeId,ttl,pin}  须登录
public ShareCreateResponse create(@LoginUser long userId, CreateShareRequest req) {
    return shareLinkService.create(userId, req);
}

// GET /api/share/{code}/meta  匿名
public ShareMetaResponse meta(@PathVariable String code) {
    return shareLinkService.meta(code);
}

// POST /api/share/{code}/unlock  body:{pin}  匿名
public void unlock(@PathVariable String code, UnlockShareRequest body, HttpServletResponse resp) {
    // 步骤1：校验 PIN，Redis 写入 grant token
    String token = shareLinkService.unlock(code, body.getPin());
    // 步骤2：Set-Cookie HttpOnly grant Cookie（Path=/api/share）
    resp.addCookie(buildGrantCookie(token));
}

// GET /api/share/{code}/download  Cookie: grant  匿名
public ResponseEntity<Resource> download(@PathVariable String code, @CookieValue grant) {
    return shareLinkService.download(code, grant);
}

// GET /api/share/{code}/preview-pdf  Cookie: grant  匿名
public ResponseEntity<Resource> previewOfficePdf(@PathVariable String code, @CookieValue grant) {
    return shareLinkService.previewOfficePdf(code, grant);
}


// ========== 2.6 AdminController ==========
// 源文件: controller/AdminController.java  前缀 /api/admin  须平台 admin

// POST /api/admin/gallery/projects/{nodeId}/unpublish
public FsNodeVO unpublishPublicNode(@LoginUser long adminId, long nodeId) {
    return fsNodeService.adminUnpublishPublicProject(adminId, nodeId);
}

// GET /api/admin/access-logs?page=&size=
public PageResult<ApiAccessLog> accessLogs(int page, int size) {
    // 步骤1：分页参数规范化
    // 步骤2：ApiAccessLogMapper.selectPage 按 occurred_at 降序
    // 步骤3：封装 PageResult 返回
}


// ========== 2.7 ApiExceptionHandler ==========
// 源文件: controller/ApiExceptionHandler.java  @RestControllerAdvice

// FileNameConflictException → 409 + {code:FILE_NAME_CONFLICT, existingFileId, fileName}
// OfficePreviewFailedException → 422
// IllegalArgumentException → 400（ExceptionMessageTranslator 转中文）
// IllegalStateException → 500
// MaxUploadSizeExceededException → 413
// 其他 → 500 兜底


// =============================================================================
// 三、后端 Service 层 — AuthServiceImpl
// =============================================================================
// 源文件: service/impl/AuthServiceImpl.java

public AuthUserVO register(RegisterRequest req) {
    // 步骤1：校验昵称、邮箱非空，密码至少 4 位
    // 步骤2：按 email 查重，已存在则抛「邮箱已被注册」
    // 步骤3：BCrypt 哈希密码
    // 步骤4：insert user 表，role 默认 user
    // 步骤5：转 AuthUserVO（不含 passwordHash）
    return toVo(u);
}

public AuthUserVO login(LoginRequest req) {
    // 步骤1：校验邮箱密码非空
    // 步骤2：按 email 查 user
    // 步骤3：BCrypt.matches 校验密码
    // 步骤4：返回 AuthUserVO
    return toVo(u);
}


// =============================================================================
// 三、后端 Service 层 — FsNodeServiceImpl（公开方法）
// =============================================================================
// 源文件: service/impl/FsNodeServiceImpl.java

// --- 启动 ---
@PostConstruct void initStorageAndMembers() {
    // 步骤1：创建 data/file-storage 根目录
    // 步骤2：backfillProjectOwnerAdmins() 为历史项目根补 project_admin 成员行
}

// --- 读权限 ---
public void requireReadableNode(long userId, FsNode node) {
    // 步骤1：requireUser 用户存在
    // 步骤2：requireProjectRead 对节点所在项目有读权限
}

// --- 列目录 ---
public List<FsNodeVO> listChildren(long userId, long parentId) {
    // 步骤1：requireUser
    if (parentId == ROOT_PARENT_ID) {
        // 步骤2a：查 project_member 得参与的项目根 id 列表
        // 步骤3a：SQL 合并「owner_user_id=我」OR「id IN 参与列表」的一级文件夹
        // 步骤4a：每条 VO 附加 projectMemberNames 摘要
        return voList;
    } else {
        // 步骤2b：assertParentFolderReadable
        // 步骤3b：walkToProjectRoot 得 namespaceOwner
        // 步骤4b：按 parentId + ownerUserId 查子节点，文件夹优先排序
        return voList;
    }
}

// --- 新建文件夹 / 项目 ---
@Transactional public FsNodeVO createFolder(long userId, long parentId, String name) {
    // 步骤1：requireUser + assertParentFolderWritable
    // 步骤2：sanitizeName + assertNameAvailable
    // 步骤3：namespaceOwnerForParent 决定 owner_user_id
    // 步骤4：inheritPublicFromParentFolder 决定 is_public
    // 步骤5：insert fs_node TYPE_FOLDER
    // 步骤6：若在空间根创建 → ensureProjectAdminMembership(创建者 project_admin)
    return FsNodeVO.from(folder);
}

// --- 重命名 ---
@Transactional public FsNodeVO renameFolder(long userId, long nodeId, String newName) {
    // 步骤1：节点须为 FOLDER
    // 步骤2：项目根 → requireProjectAdmin；子文件夹 → requireProjectWrite
    // 步骤3：sanitizeName + assertSiblingNameFreeExcept
    // 步骤4：updateById name + updated_at
    return FsNodeVO.from(node);
}

// --- 单文件上传 ---
@Transactional public FsNodeVO uploadFile(long userId, long parentId, MultipartFile file) {
    // 步骤1：禁止 parentId=0（须先进项目）
    // 步骤2：assertParentFolderWritable
    // 步骤3：sanitizeName + assertChildNameForNewUpload（同名抛 FileNameConflictException 409）
    // 步骤4：newStorageKey → transferTo 磁盘
    // 步骤5：insert fs_node TYPE_FILE（storageKey, contentType, uploadedByName...）
    return FsNodeVO.from(node);
}

// --- ZIP 上传 ---
@Transactional public FsNodeVO uploadZipFolder(long userId, long parentId, MultipartFile zip) {
    // 步骤1：写权限 + 须 .zip 后缀
    // 步骤2：在父目录 insert 根文件夹（ZIP 文件名去 .zip）
    // 步骤3：临时目录 ZipArchiveSupport.unzipStreamToDirectory
    // 步骤4：persistTreeFromDisk 递归落库+落盘
    // 步骤5：finally 删临时目录
    return FsNodeVO.from(rootFolder);
}

// --- 浏览器文件夹上传 ---
@Transactional public FsNodeVO uploadFolder(long userId, long parentId, List files, List paths, String rootNameHint) {
    // 步骤1：写权限；files.size ≤ 400
    // 步骤2：buildFolderUploadPlan 解析相对路径、推断根文件夹名
    // 步骤3：临时目录按 paths 写入文件树
    // 步骤4：insert 根文件夹 + persistTreeFromDisk
    // 步骤5：finally 删临时目录
    return FsNodeVO.from(rootFolder);
}

// --- 替换文件 ---
@Transactional public FsNodeVO replaceFileContent(long userId, long nodeId, MultipartFile file) {
    // 步骤1：requireProjectWrite
    // 步骤2：新内容写 .staging/{uuid}.part
    // 步骤3：旧文件 copy 到 revision storageKey，insert fs_node_revision
    // 步骤4：新文件覆盖原 storageKey 路径
    // 步骤5：更新 node 元数据；deletePreviewPdfQuietly
    // 步骤6：pruneOldestRevisions 保留最新 20 条
    return FsNodeVO.from(node);
}

// --- 历史版本 ---
public List<FsNodeRevisionVO> listFileRevisions(long userId, long nodeId) {
    // 步骤1：读权限 + 须 TYPE_FILE
    // 步骤2：按 nodeId orderByDesc id 查 revision 表转 VO
}

public ResponseEntity<Resource> downloadFileRevision(long userId, long nodeId, long revisionId) {
    // 步骤1：读权限
    // 步骤2：校验 revision 属于该 nodeId
    // 步骤3：FileSystemResource + attachment Content-Disposition
}

public ResponseEntity<Resource> previewOfficeAsPdfForRevision(long userId, long nodeId, long revisionId) {
    // 步骤1：读权限 + 加载 revision
    // 步骤2：assertOfficePreviewableForRevision（类型+50MB）
    // 步骤3：buildOrConvertOfficePdfForRevision（按 revisionId 缓存路径+锁）
}

public void deleteFileRevision(long userId, long fileId, long revisionId) {
    // 步骤1：写权限
    // 步骤2：删 revision 行 + 物理文件 + revision 预览 PDF 缓存
}

// --- 删除 ---
@Transactional public void deleteNode(long userId, long id) {
    // 步骤1：assertCanDeleteNode（文件夹仅 admin；文件 admin 或本人上传）
    // 步骤2：deleteSubtreeRecursively 后序递归
}

// --- 下载 ---
public ResponseEntity<Resource> download(long userId, long id) {
    // 步骤1：读权限 + TYPE_FILE
    // 步骤2：FileSystemResource + attachment
}

// --- Office 预览（三种入口共用 buildOrConvertOfficePdf）---
public ResponseEntity<Resource> previewOfficeAsPdf(long userId, long fileId) {
    // 步骤1：读权限 + assertOfficePreviewable
    // 步骤2：buildOrConvertOfficePdf
}

public ResponseEntity<Resource> previewPublicOfficeAsPdf(long ownerUserId, long fileId) {
    // 步骤1：节点须 is_public 且 owner 匹配
    // 步骤2：buildOrConvertOfficePdf
}

public ResponseEntity<Resource> previewOfficeAsPdfForSharedFile(FsNode node) {
    // 步骤1：调用方已校验分享 grant
    // 步骤2：assertOfficePreviewable + buildOrConvertOfficePdf
}

private ResponseEntity<Resource> buildOrConvertOfficePdf(FsNode node, Path source) {
    // 步骤1：Files.size ≤ maxSourceBytes
    // 步骤2：cachedPreviewPdfPath = preview-lo/{ownerUserId}/{nodeId}.pdf
    // 步骤3：synchronized(nodeId锁) 双重检查缓存 mtime ≥ 源文件
    // 步骤4：未命中 → libreOfficePdfConverter.convertToPdf
    // 步骤5：inlinePdfResponse Content-Disposition:inline
}

// --- 广场公开 ---
@Transactional public FsNodeVO setPublicRecursive(long userId, long nodeId, boolean isPublic) {
    // 步骤1：requireProjectAdmin + 须空间根下一级项目文件夹
    // 步骤2：applyPublicRecursive 先序递归更新 is_public
}

@Transactional public FsNodeVO adminUnpublishPublicProject(long adminUserId, long nodeId) {
    // 步骤1：平台 admin 角色
    // 步骤2：节点须已 is_public
    // 步骤3：applyPublicRecursive(false)
}

public PageResult<GalleryProjectVO> listPublicProjects(int page, int size) {
    // 步骤1：page≥1, size≤50
    // 步骤2：countPublicRootFolders + selectPublicRootFolders 分页
    // 步骤3：附加 ownerName 封装 GalleryProjectVO
}

public List<FsNodeVO> listPublicChildren(long ownerUserId, long parentId) {
    // 步骤1：owner 用户存在
    // 步骤2：parentId=0 → 该用户 is_public 的一级项目；否则 parent 须公开且 owner 匹配
    // 步骤3：查子节点转 VO
}

public ResponseEntity<Resource> downloadPublicFile(long ownerUserId, long fileId) {
    // 步骤1：TYPE_FILE + owner 一致 + is_public
    // 步骤2：attachment 下载
}

// --- 项目成员 ---
public List<ProjectMemberVO> listProjectMembers(long actorUserId, long projectRootId) {
    // 步骤1：须项目根 + requireProjectRead
    // 步骤2：buildProjectMemberVoList
}

public List<ProjectMemberVO> listPublicProjectMembers(long projectRootId) {
    // 步骤1：项目根须 is_public
    // 步骤2：buildProjectMemberVoList
}

public void addProjectMember(long actorUserId, long projectRootId, long targetUserId, String role) {
    // 步骤1：requireProjectAdmin
    // 步骤2：不可添加创建者；target 用户存在
    // 步骤3：role 仅 member / project_admin；防重复 insert project_member
}

public void removeProjectMember(long actorUserId, long projectRootId, long targetUserId) {
    // 步骤1：requireProjectAdmin
    // 步骤2：不可移除创建者
    // 步骤3：delete project_member 行
}

public List<UserCandidateVO> searchProjectMemberCandidates(long actorUserId, long projectRootId, String q) {
    // 步骤1：requireProjectAdmin
    // 步骤2：按 name/email LIKE 查 user，排除已是成员者
    // 步骤3：转 UserCandidateVO 列表（上限条数）
}

public String getMyProjectRole(long userId, long projectRootId) {
    // 步骤1：创建者 → project_admin
    // 步骤2：否则查 project_member.role；非成员 → null
}


// =============================================================================
// 四、FsNodeServiceImpl 私有辅助方法
// =============================================================================

private FsNode walkToProjectRoot(FsNode node) {
    // 步骤1：cur = node，循环 while parentId != 0
    // 步骤2：沿 parentId selectById 向上，父须为 FOLDER
    // 步骤3：guard>1000 防死循环；返回一级项目根文件夹
}

private ProjectAccess accessInProject(long userId, long projectRootId) {
    // 步骤1：校验 projectRootId 为 parentId=0 的 FOLDER
    // 步骤2：创建者 → ADMIN
    // 步骤3：查 project_member：project_admin→ADMIN，member→WRITE，无→NONE
}

private void requireProjectRead/Write/Admin(...) {
    // 步骤：walkToProjectRoot → accessInProject → 档位不足抛「无权访问/修改/需要管理员」
}

private void persistTreeFromDisk(..., long parentId, Path dir) {
    // 步骤1：list 目录项，文件夹优先、名称排序
    // 步骤2：子目录 → insertFolderRecord + 递归
    // 步骤3：文件 → copy 到 storageKey + insert TYPE_FILE
}

private void deleteSubtreeRecursively(long id) {
    // 步骤1：先递归删所有子节点（后序）
    // 步骤2：若 FILE：删 revisions + preview PDF + 物理文件
    // 步骤3：deleteById 当前节点
}

private void applyPublicRecursive(long id, boolean isPublic) {
    // 步骤1：update 当前节点 is_public
    // 步骤2：对每个子节点递归 applyPublicRecursive（先序）
}

private void assertChildNameForNewUpload(...) {
    // 步骤1：findChildNode 同名
    // 步骤2：若存在 TYPE_FILE → throw FileNameConflictException(existingFileId, name)
}

private String newStorageKey(String originalName) {
    // 步骤1：UUID + 保留扩展名
    // 步骤2：路径形如 2026/05/19/{uuid}.ext
}

private static String sanitizeName(String name) {
    // 步骤1：去路径分隔符、控制字符；trim；空则 unnamed
}


// =============================================================================
// 三、后端 Service 层 — ShareLinkServiceImpl
// =============================================================================
// 源文件: service/impl/ShareLinkServiceImpl.java

public ShareCreateResponse create(long userId, CreateShareRequest req) {
    // 步骤1：nodeId 须 TYPE_FILE + requireReadableNode
    // 步骤2：物理文件存在
    // 步骤3：resolveTtlSeconds(3h/24h/72h)；PIN 可选 BCrypt
    // 步骤4：ShareRedisPayload 序列化 JSON
    // 步骤5：persistWithUniqueCode SETNX Redis fs:share:link:{code} TTL
    // 步骤6：返回 code + path(/s/{code}) + ttlSeconds
}

public ShareMetaResponse meta(String rawCode) {
    // 步骤1：normalizeCode 大写 trim
    // 步骤2：loadPayload from Redis
    // 步骤3：getExpire 得剩余 TTL
    // 步骤4：返回 fileName, contentType, requiresPin, ttlSecondsRemaining
}

public String unlock(String rawCode, String pin) {
    // 步骤1：loadPayload；须已设 pinHash
    // 步骤2：BCrypt.matches(pin, pinHash)
    // 步骤3：UUID grant token → Redis fs:share:grant:{token}=code TTL 30min
    return token;
}

public ResponseEntity<Resource> download(String rawCode, String grantCookie) {
    // 步骤1：loadPayload + assertGrantIfNeeded（有 PIN 须有效 grant Cookie）
    // 步骤2：校验 fileId/owner 与 DB 一致
    // 步骤3：FileSystemResource attachment
}

public ResponseEntity<Resource> previewOfficePdf(String rawCode, String grantCookie) {
    // 步骤1：assertGrantIfNeeded
    // 步骤2：委托 fsNodeService.previewOfficeAsPdfForSharedFile(node)
}

private void assertGrantIfNeeded(ShareRedisPayload p, String code, String grant) {
    // 步骤1：无 PIN → 直接 return
    // 步骤2：grant Cookie 空 → 请先验证 PIN
    // 步骤3：Redis grant 须绑定当前 code
}


// =============================================================================
// 五、安全 / AOP / 配置 / 预览 / 工具
// =============================================================================

// ========== ApiSessionAuthFilter ==========
// 源文件: security/ApiSessionAuthFilter.java
protected void doFilterInternal(...) {
    // 步骤1：OPTIONS 预检直接放行
    // 步骤2：Session 读 FS_UID / FS_ROLE
    // 步骤3：isAnonymousApi → 可选写入 REQUEST_USER_ID 后放行
    //        白名单：/api/fs/gallery/**、POST login/register、/api/share/{code}/meta|unlock|download|preview-pdf
    // 步骤4：非白名单且未登录 → 401 JSON
    // 步骤5：写入 request 属性 userId/role
    // 步骤6：/api/admin/** 非 admin → 403
    // 步骤7：chain.doFilter 进入 Controller
}

// ========== LoginUserArgumentResolver ==========
// 源文件: security/LoginUserArgumentResolver.java
public Object resolveArgument(...) {
    // 步骤1：从 request.getAttribute(REQUEST_USER_ID) 取 Long
    // 步骤2：null 则 IllegalStateException（Filter 应已拦未登录）
    return userId;
}

// ========== ApiAccessLogAspect ==========
// 源文件: aop/ApiAccessLogAspect.java
@Around("controller 包 public 方法，排除 ApiExceptionHandler")
public Object aroundController(ProceedingJoinPoint pjp) {
    // 步骤1：nanoTime 开始
    // 步骤2：pjp.proceed()
    // 步骤3：finally → record() 组装 ApiAccessLog insert
    // 步骤4：ApiOperationLabels 解析中文操作名
}

// ========== LibreOfficePdfConverter ==========
// 源文件: preview/LibreOfficePdfConverter.java
public void convertToPdf(Path sourceFile, Path targetPdf) {
    // 步骤1：enabled + soffice 路径存在
    // 步骤2：临时目录 fs-lo-*，copy 源为 source{ext}
    // 步骤3：ProcessBuilder: soffice --headless --nologo --convert-to pdf --outdir temp source
    // 步骤4：waitFor(timeout)；超时 destroyForcibly
    // 步骤5：检查 exit=0 且 source.pdf 非空
    // 步骤6：copy 到 targetPdf 缓存路径
    // 步骤7：finally 删临时目录
}

// ========== OfficePreviewSupport ==========
// isOfficeDocumentForPreview(fileName, contentType) — MIME 或扩展名白名单
// extensionOf(fileName) — 取小写后缀

// ========== ZipArchiveSupport ==========
// unzipStreamToDirectory — 防 Zip Slip；detectCharset UTF-8/GBK

// ========== 配置类（读 application-learn.yml）==========
// FileStorageProperties — file.storage.root, max bytes
// LibreOfficePreviewProperties — enabled, soffice-path, max-source-bytes, timeout-seconds
// AppCorsProperties + WebCorsConfig — 开发跨域 + credentials
// WebMvcSessionConfig — 注册 LoginUserArgumentResolver
// PasswordEncoderConfig — BCrypt Bean
// FsNodePublicColumnPatch / UserPasswordColumnPatch — 老库 DDL 补丁


// =============================================================================
// 六、Entity / DTO 数据形状（API JSON 不含 storageKey）
// =============================================================================
//
// FsNode 表 → FsNodeVO: id, parentId, name, nodeType(FOLDER|FILE), sizeBytes,
//   contentType, ownerUserId, uploadedByName, isPublic, createdAt, updatedAt, projectMemberNames(根列表附加)
//
// FsNodeRevision → FsNodeRevisionVO: id, nodeId, fileNameSnapshot, sizeBytes, contentType, createdByName, createdAt
//
// User → AuthUserVO: id, name, email, role
// ProjectMember + User → ProjectMemberVO: userId, name, email, role
//
// RegisterRequest: name, email, password
// LoginRequest: email, password
// CreateShareRequest: nodeId, ttl(3h|24h|72h), pin(可选)
// ShareCreateResponse: code, path, ttlSeconds
// ShareMetaResponse: fileName, contentType, requiresPin, ttlSecondsRemaining
// PageResult<T>: content, totalElements, totalPages, page, size


// =============================================================================
// 七、前端 API / 路由 / 工具
// =============================================================================

// ========== src/api/http.js ==========
// authApi    baseURL /api           withCredentials
// fsApi      baseURL /api/fs
// galleryApi baseURL /api/fs/gallery
// shareCreateApi baseURL /api/share
// adminApi   baseURL /api/admin

// ========== src/utils/auth.js ==========
function getStoredUser() {
    // 步骤1：localStorage.getItem('fs_user')
    // 步骤2：JSON.parse 返回 {id,name,email,role} 或 null
}
function setStoredUser(user) { localStorage.setItem('fs_user', JSON.stringify(user)); }
function clearStoredUser() { localStorage.removeItem('fs_user'); }
function isAdminUser() { return getStoredUser()?.role === 'admin'; }

// ========== src/utils/filePreview.js ==========
function getFilePreviewKind(fileName, mime) {
    // 步骤1：mime 判断 image/video/pdf/office
    // 步骤2：扩展名兜底 .jpg/.mp4/.pdf/.docx 等
    // 步骤3：不支持返回 null
}
function mimeForPreviewKind(kind, fileName) {
    // 步骤：为 Blob 补 application/pdf、video/mp4 等 type
}

// ========== src/router/index.js ==========
// 路由表：
//   /login, /register (guest)
//   /s/:code ShareView (public)
//   /my-files, /explore, /admin/logs (requiresAuth; admin/logs 须 adminOnly)
//   /preview/mine/:nodeId, /preview/mine/:nodeId/revision/:revisionId
//   /preview/public/:ownerId/:nodeId, /preview/share/:code
//
router.beforeEach((to) => {
    // 步骤1：meta.public → 放行
    // 步骤2：requiresAuth 且无 storedUser → redirect login?redirect=
    // 步骤3：adminOnly 且非 admin → my-files
    // 步骤4：guest 且已登录 → my-files
});


// =============================================================================
// 八、前端 Views — LoginView.vue
// =============================================================================
// 源文件: src/views/LoginView.vue

async function submit() {
    // 步骤1：校验 email/password 非空
    // 步骤2：POST /api/auth/login {email,password}
    // 步骤3：setStoredUser(data)
    // 步骤4：router.push my-files 或 query.redirect
}


// =============================================================================
// 八、前端 Views — RegisterView.vue
// =============================================================================
async function submit() {
    // 步骤1：校验 name/email/password
    // 步骤2：POST /api/auth/register
    // 步骤3：setStoredUser → 跳转 my-files
}


// =============================================================================
// 八、前端 Views — MyFilesView.vue（核心）
// =============================================================================
// 源文件: src/views/MyFilesView.vue
// 状态：crumbs 面包屑、nodes 列表、myProjectRole、多个 modal

async function loadList() {
    // 步骤1：GET /api/fs/nodes?parentId=currentParentId
    // 步骤2：nodes = data；失败显示 errorMsg
}

function enterFolder(row) {
    // 步骤1：FOLDER 则 crumbs.push({id,name}) → watch 触发 loadList
}

function goCrumb(index) {
    // 步骤1：crumbs 截断到 index → loadList
}

async function createFolder() {
    // 步骤1：POST /api/fs/folders {parentId,name}
    // 步骤2：loadList 刷新
}

async function createProjectRoot() {
    // 步骤1：prompt 项目名
    // 步骤2：POST /api/fs/projects {name}
    // 步骤3：loadList
}

async function onFileChange(ev) {
    // 步骤1：FormData append file
    // 步骤2：POST /api/fs/files?parentId=
    // 步骤3：若 409 FILE_NAME_CONFLICT → confirm 替换 → submitReplace(existingFileId, file)
    // 步骤4：loadList
}

async function submitReplace(nodeId, file) {
    // 步骤1：POST /api/fs/nodes/{nodeId}/replace multipart
    // 步骤2：关闭 modify modal；loadList
}

async function onZipChange(ev) {
    // 步骤1：POST /api/fs/upload-zip?parentId= multipart
}

async function onFolderChange(ev) {
    // 步骤1：buildFolderUploadPayload 从 input.files 提取 relative paths
    // 步骤2：POST /api/fs/upload-folder?parentId=&rootName= 多文件
}

async function downloadFile(row) {
    // 步骤1：fetch GET /api/fs/nodes/{id}/download credentials:include
    // 步骤2：blob → 临时 <a download> 触发保存
}

function openPreview(row) {
    // 步骤1：getFilePreviewKind 不支持则 alert
    // 步骤2：window.open /preview/mine/{nodeId}?name=&ct=
}

async function openHistoryModal(row) {
    // 步骤1：GET /api/fs/nodes/{id}/revisions
    // 步骤2：展示 historyList
}

async function deleteRevision(rev) {
    // 步骤1：DELETE /api/fs/nodes/{id}/revisions/{revId}
}

async function downloadRevision(rev) {
    // 步骤1：fetch GET .../revisions/{revId}/download
}

function openRevisionPreview(rev) {
    // 步骤1：window.open /preview/mine/{nodeId}/revision/{revId}
}

async function renameFolderRow(row) {
    // 步骤1：prompt 新名
    // 步骤2：PATCH /api/fs/nodes/{id}/name {name}
}

async function removeNode(row) {
    // 步骤1：confirm
    // 步骤2：DELETE /api/fs/nodes/{id}
}

async function toggleGalleryPublic(row, makePublic) {
    // 步骤1：PATCH /api/fs/nodes/{id}/public {public: true/false}
    // 步骤2：仅项目根 + project_admin 可见按钮
}

async function submitShareCreate() {
    // 步骤1：POST /api/share {nodeId, ttl, pin}
    // 步骤2：展示 shareUrl = origin + /s/{code}
}

async function openMembersModal() {
    // 步骤1：GET /api/fs/projects/{rootId}/members
    // 步骤2：GET member-candidates?q= 搜索添加
    // 步骤3：POST/DELETE members
}

watch(projectRootId) {
    // 步骤1：进入项目时 GET /api/fs/projects/{rootId}/my-role
    // 步骤2：myProjectRole = data.role
}


// =============================================================================
// 八、前端 Views — ExploreView.vue（广场）
// =============================================================================
async function loadProjects() {
    // 步骤1：GET /api/fs/gallery/projects?page=&size=
}

async function openProject(p) {
    // 步骤1：记录 currentOwnerId, projectRootId
    // 步骤2：loadNodes GET /api/fs/gallery/{ownerId}/nodes?parentId=
}

async function downloadPublic(row) {
    // 步骤1：fetch GET /api/fs/gallery/{ownerId}/files/{id}/download
}

function openPublicPreview(row) {
    // 步骤1：window.open /preview/public/{ownerId}/{nodeId}
}

async function onAdminUnpublishProject(p) {
    // 步骤1：confirm
    // 步骤2：POST /api/admin/gallery/projects/{nodeId}/unpublish
    // 步骤3：loadProjects
}


// =============================================================================
// 八、前端 Views — FilePreviewView.vue（统一预览）
// =============================================================================
async function loadBody() {
    // 步骤1：按 route.name 分支：
    //   file-preview-share → loadSharePreview
    //   file-preview-revision → loadRevisionPreview
    //   file-preview-public → loadPublicPreview
    //   file-preview-mine → loadMinePreview
}

async function loadSharePreview() {
    // 步骤1：GET /api/share/{code}/meta
    // 步骤2：getFilePreviewKind
    // 步骤3a：office → GET /api/share/{code}/preview-pdf → blob URL iframe
    // 步骤3b：其他 → GET /api/share/{code}/download → blob + kind
}

async function loadRevisionPreview() {
    // 步骤1：office → GET /api/fs/nodes/{id}/revisions/{revId}/preview-pdf
    // 步骤2：其他 → GET .../download
}

async function loadMinePreview() {
    // 步骤1：office → GET /api/fs/nodes/{id}/preview-pdf
    // 步骤2：其他 → GET /api/fs/nodes/{id}/download
}

async function loadPublicPreview() {
    // 步骤1：office → GET /api/fs/gallery/{ownerId}/files/{id}/preview-pdf
    // 步骤2：其他 → GET .../download
}

function closeOrBack() {
    // 步骤1：window.close；失败则 router 回 share 或 my-files
}

onUnmounted → revoke() 释放 blob URL


// =============================================================================
// 八、前端 Views — ShareView.vue
// =============================================================================
async function loadMeta() {
    // 步骤1：GET /api/share/{code}/meta
    // 步骤2：展示 fileName、requiresPin、剩余 TTL
}

async function submitPin() {
    // 步骤1：POST /api/share/{code}/unlock {pin}
    // 步骤2：服务端 Set-Cookie grant；前端 unlocked=true
}

async function download() {
    // 步骤1：fetch GET /api/share/{code}/download credentials:include（带 grant Cookie）
}

function openPreview() {
    // 步骤1：router /preview/share/{code} 或 window.open
}


// =============================================================================
// 八、前端 Views — AdminLogsView.vue
// =============================================================================
async function load() {
    // 步骤1：GET /api/admin/access-logs?page=&size=20
    // 步骤2：渲染表格：时间、方法、URI、用户、耗时、成功、操作标签
}


// =============================================================================
// 九、前端 Components / Layouts / 入口
// =============================================================================

// AppNavBar.vue
async function logout() {
    // 步骤1：POST /api/auth/logout
    // 步骤2：clearStoredUser
    // 步骤3：router.push login
}
function goNewProject() {
    // 步骤1：router.push {name:'my-files', query:{newProject:'1'}}
}

// GalleryPlazaPanel.vue — 纯展示，emit: open-project, admin-unpublish, prev-page, next-page

// ProjectMembersViewModal.vue — 展示 members 列表，roleLabel 转中文，emit close

// GalleryShellLayout.vue — 背景 + slot，无逻辑

// GalleryWorkspaceLayout.vue — GalleryShellLayout + router-view 过渡动画

// App.vue
// 步骤1：route.meta.requiresAuth 时显示 AppNavBar
// 步骤2：router-view 渲染页面

// main.js
// 步骤1：createApp(App).use(router).mount('#app')
// 步骤2：import gallery-shell.css


// =============================================================================
// 附录 A：一次「上传并预览 Office」全链路
// =============================================================================
//
// 前端 MyFilesView.onFileChange
//   → POST /api/fs/files (multipart)
//   → Filter 鉴权 → AOP 日志 → FsController.uploadFile
//   → FsNodeServiceImpl.uploadFile: 写权限→落盘→insert fs_node
//   → 返回 FsNodeVO JSON
//
// 用户点预览 openPreview
//   → 新窗口 FilePreviewView /preview/mine/{id}
//   → getFilePreviewKind → office
//   → GET /api/fs/nodes/{id}/preview-pdf
//   → previewOfficeAsPdf → buildOrConvertOfficePdf
//   → 缓存 miss → LibreOfficePdfConverter ProcessBuilder soffice
//   → inline PDF ResponseEntity
//   → 前端 blob → iframe 显示
//
// Nginx：proxy_read_timeout 600s 防转换超时 504


// =============================================================================
// 附录 B：Mapper / Redis / Session 职责速查
// =============================================================================
//
// FsNodeMapper — fs_node CRUD + countPublicRootFolders + selectPublicRootFolders
// FsNodeRevisionMapper — fs_node_revision CRUD
// ProjectMemberMapper — project_member CRUD
// UserMapper — user CRUD
// ApiAccessLogMapper — api_access_log insert + Admin 分页
//
// Redis：
//   Spring Session — FSSESSION Cookie → Session 属性 FS_UID/FS_ROLE
//   fs:share:link:{code} — ShareRedisPayload JSON + TTL
//   fs:share:grant:{token} — 分享码 + 30min TTL
//
// 磁盘：
//   data/file-storage/{年/月/日}/{uuid}.ext — 原件
//   data/file-storage/preview-lo/{userId}/{nodeId}.pdf — LO 预览缓存
//   data/file-storage/preview-lo/{userId}/rev-{revisionId}.pdf — 历史预览缓存
