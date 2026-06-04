/*
 * 第四章论文用「结构伪代码」示例（非可运行代码，仅供排版/截图，不参与编译）
 * 各节注释标明对应真实源码路径；方法体内以「步骤」标注逻辑顺序。
 */

// ========== 4.2.1 会话建立 ==========
// 源文件: controller/AuthController.java
// 功能：注册/登录成功后写入服务端会话
private static void establishSession(HttpServletRequest request, AuthUserVO vo) {
    // 步骤1：获取或创建服务端会话（与 Cookie FSSESSION 绑定，存 Redis）
    HttpSession session = request.getSession(true);
    // 步骤2：写入当前登录用户编号，供后续过滤器与 @LoginUser 使用
    session.setAttribute(FS_UID, vo.getId());
    // 步骤3：写入平台角色（user/admin），缺省为普通用户
    String role = (vo.getRole() != null) ? vo.getRole() : "user";
    session.setAttribute(FS_ROLE, role);
}

// ========== 4.2.2 接口鉴权 ==========
// 源文件: security/ApiSessionAuthFilter.java
// 功能：在请求进入 Controller 前统一校验登录态与管理员权限
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain chain) throws IOException {
    final String method = request.getMethod();
    final String path = request.getRequestURI();
    // 步骤1：浏览器预检请求直接放行，不校验登录
    if ("OPTIONS".equalsIgnoreCase(method)) {
        chain.doFilter(request, response);
        return;
    }
    // 步骤2：从 Session 读取已登录用户（未登录则为 null）
    Long userId = readUserId(request.getSession(false));
    String role = readRole(request.getSession(false));
    // 步骤3：白名单路径（广场、登录注册、分享访客）允许匿名访问
    if (isAnonymousApi(method, path)) {
        if (userId != null) {
            // 步骤3a：已登录访客仍写入 request，便于记日志或业务取用
            request.setAttribute(REQUEST_USER_ID, userId);
            request.setAttribute(REQUEST_ROLE, role);
        }
        chain.doFilter(request, response);
        return;
    }
    // 步骤4：非白名单且未登录 → 401
    if (userId == null) {
        writeJson(response, 401, "未登录或会话已过期");
        return;
    }
    // 步骤5：已登录，把用户身份放入本次请求上下文
    request.setAttribute(REQUEST_USER_ID, userId);
    request.setAttribute(REQUEST_ROLE, role);
    // 步骤6：管理端路径须 admin 角色，否则 403
    if (path.startsWith("/api/admin/") && !isAdmin(role)) {
        writeJson(response, 403, "需要管理员权限");
        return;
    }
    // 步骤7：鉴权通过，进入 Controller
    chain.doFilter(request, response);
}

// ========== 4.3 访问日志 ==========
// 源文件: aop/ApiAccessLogAspect.java
// 功能：环绕切面记录每次 Controller 调用的耗时与结果
@Around("controller 包公开方法")
public Object aroundController(ProceedingJoinPoint pjp) throws Throwable {
    // 步骤1：记录开始时间，用于计算接口耗时
    final long startNs = System.nanoTime();
    Object result = null;
    Throwable thrown = null;
    try {
        // 步骤2：执行真实 Controller 方法
        result = pjp.proceed();
        return result;
    } catch (Throwable t) {
        // 步骤3：捕获异常，标记失败，继续向外抛出
        thrown = t;
        throw t;
    } finally {
        // 步骤4：无论成功失败都写日志（finally 保证一定执行）
        final long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        try {
            // 步骤4a：组装日志行并落库 api_access_log
            ApiAccessLog row = buildLogRow(pjp, durationMs, result, thrown);
            apiAccessLogMapper.insert(row);
        } catch (Exception e) {
            // 步骤4b：写库失败仅打 warn，不影响主业务
            log.warn("写入 api_access_log 失败", e);
        }
    }
}

// ========== 4.4.1 列目录 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：按父节点列出子项；根目录返回「我的项目」与「参与的项目」
public List<FsNodeVO> listChildren(long userId, long parentId) {
    // 步骤1：校验用户有效
    requireUser(userId);
    if (parentId == ROOT_PARENT_ID) {
        // 步骤2a：空间根 —— 合并「我创建的项目」与「被邀请参与的项目」
        List<FsNode> owned = queryOwnedProjectRoots(userId);
        List<FsNode> joined = queryMemberProjectRoots(userId);
        return mergeToVoList(owned, joined);
    } else {
        // 步骤2b：项目内 —— 校验父文件夹读权限
        assertParentFolderReadable(userId, parentId);
        // 步骤3b：按项目命名空间 owner 查询子节点并转 VO
        FsNode parent = fsNodeMapper.selectById(parentId);
        long nsOwner = walkToProjectRoot(parent).getOwnerUserId();
        return fsNodeMapper.selectByParent(parentId, nsOwner)
                .stream().map(FsNodeVO::from).toList();
    }
}

// ========== 4.4.2 上传文件 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：向指定文件夹上传单个文件并落库
@Transactional
public FsNodeVO uploadFile(long userId, long parentId, MultipartFile file) {
    // 步骤1：禁止在空间根直接上传，须先进入某个项目
    if (parentId == ROOT_PARENT_ID) {
        throw new IllegalArgumentException("须先建项目再上传");
    }
    // 步骤2：校验对父文件夹具备写权限
    assertParentFolderWritable(userId, parentId);
    // 步骤3：清洗文件名，检查同目录同名（冲突则抛 409）
    String safeName = sanitizeName(file.getOriginalFilename());
    assertChildNameForNewUpload(userId, parentId, safeName);
    // 步骤4：生成存储相对路径，写入磁盘
    String storageKey = newStorageKey(safeName);
    Path absolute = storageRoot.resolve(storageKey);
    file.transferTo(absolute);
    // 步骤5：插入 fs_node 记录，继承父目录公开属性
    FsNode node = insertFileNode(parentId, safeName, storageKey, userId);
    return FsNodeVO.from(node);
}

// ========== 4.4.3 替换与历史版本 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：覆盖文件内容并保留历史快照（最多 20 条）
@Transactional
public FsNodeVO replaceFileContent(long userId, long nodeId, MultipartFile file) {
    // 步骤1：加载文件节点，校验项目写权限
    FsNode node = fsNodeMapper.selectById(nodeId);
    requireProjectWrite(userId, node);
    Path oldPath = storageRoot.resolve(node.getStorageKey());
    // 步骤2：新内容先写入临时目录，避免直接覆盖失败
    Path tempNew = writeToStaging(file);
    // 步骤3：把旧文件复制到历史路径，插入 fs_node_revision 快照行
    String revKey = newStorageKey(node.getName() + ".hist");
    Files.copy(oldPath, storageRoot.resolve(revKey));
    fsNodeRevisionMapper.insert(buildRevision(node, revKey));
    // 步骤4：用新文件覆盖当前 storage_key 指向路径
    Files.copy(tempNew, oldPath, REPLACE_EXISTING);
    // 步骤5：更新节点元数据，清理 PDF 预览缓存
    updateNodeMeta(node, file);
    deletePreviewPdfQuietly(node);
    // 步骤6：历史超过 20 条则删除最旧记录
    pruneOldestRevisions(nodeId, 20);
    return FsNodeVO.from(node);
}

// ========== 4.5.1 写权限校验 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：写操作前统一校验用户在项目内的权限档位
private void requireProjectWrite(long userId, FsNode anyInTree) {
    // 步骤1：沿父链定位一级项目根
    FsNode root = walkToProjectRoot(anyInTree);
    // 步骤2：计算用户在该项目的档位（NONE/READ/WRITE/ADMIN）
    ProjectAccess access = accessInProject(userId, root.getId());
    // 步骤3：非成员或只读 → 拒绝写操作
    if (access != ProjectAccess.WRITE && access != ProjectAccess.ADMIN) {
        throw new IllegalArgumentException("无权修改");
    }
}

// ========== 4.5.2 添加成员 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：项目管理员邀请用户加入，角色为 member 或 project_admin
public void addProjectMember(long actorUserId, long projectRootId,
                             long targetUserId, String role) {
    // 步骤1：确认目标为项目根节点
    FsNode root = fsNodeMapper.selectById(projectRootId);
    if (root == null || !isProjectRootFolder(root)) {
        throw new IllegalArgumentException("项目不存在");
    }
    // 步骤2：仅项目管理员可添加成员
    requireProjectAdmin(actorUserId, root);
    // 步骤3：创建者已有管理员权限，不可重复添加
    if (Objects.equals(targetUserId, root.getOwnerUserId())) {
        throw new IllegalArgumentException("创建者已是管理员");
    }
    // 步骤4：校验目标用户存在，role 仅允许 member 或 project_admin
    if (targetUserNotFound(targetUserId)) {
        throw new IllegalArgumentException("用户不存在");
    }
    if (!isAllowedMemberRole(role)) {
        throw new IllegalArgumentException("role 仅支持 member 或 project_admin");
    }
    // 步骤5：防重复成员后写入 project_member 表
    if (projectMemberMapper.exists(projectRootId, targetUserId)) {
        throw new IllegalArgumentException("该用户已是成员");
    }
    projectMemberMapper.insert(new ProjectMember(projectRootId, targetUserId, role));
}

// ========== 4.5.3 成员列表查询（代表）==========
// 源文件: ProjectMemberController → FsNodeServiceImpl.listProjectMembers
// 功能：项目内查询成员列表；其余 my-role/候选人/增删见正文，增删见 4.5.2
public List<ProjectMemberVO> listProjectMembers(long actorUserId, long projectRootId) {
    // 步骤1：加载并校验 projectRootId 为一级项目根
    FsNode root = fsNodeMapper.selectById(projectRootId);
    if (root == null || !isProjectRootFolder(root)) {
        throw new IllegalArgumentException("项目不存在");
    }
    // 步骤2：调用方须具备项目读权限（成员或创建者）
    requireProjectRead(actorUserId, root);
    // 步骤3：合并创建者与 project_member 行，去重排序后返回 VO
    return buildProjectMemberVoList(root);
}

// ========== 4.6 广场分页 ==========
// 源文件: controller/GalleryController.java
// 功能：匿名分页浏览已公开的一级项目
@GetMapping("/projects")
public PageResult<GalleryProjectVO> projects(int page, int size) {
    // 步骤1：接收分页参数（page 从 1 起，size 有默认值与上限）
    // 步骤2：委托业务层仅查询 is_public=true 的项目并封装分页结果
    return fsNodeService.listPublicProjects(page, size);
}

// ========== 4.6 公开下载 ==========
// 源文件: service/impl/FsNodeServiceImpl.java
// 功能：广场访客下载已公开文件（匿名接口，靠 is_public 约束）
public ResponseEntity<Resource> downloadPublicFile(long ownerUserId, long fileId) {
    // 步骤1：按 id 加载节点，必须是文件类型
    FsNode node = fsNodeMapper.selectById(fileId);
    if (node == null || !TYPE_FILE.equals(node.getNodeType())) {
        throw new IllegalArgumentException("只能下载文件");
    }
    // 步骤2：校验命名空间所有者一致且节点已公开（防猜 id 越权）
    if (!Objects.equals(node.getOwnerUserId(), ownerUserId)
            || !Boolean.TRUE.equals(node.getIsPublic())) {
        throw new IllegalArgumentException("文件未公开或不存在");
    }
    // 步骤3：读磁盘文件，设置 Content-Disposition 后返回
    Resource body = new FileSystemResource(storageRoot.resolve(node.getStorageKey()));
    return ResponseEntity.ok().body(body);
}

// ========== 4.7.1 创建分享 ==========
// 源文件: service/impl/ShareLinkServiceImpl.java
// 功能：为单个文件生成限时分享码，可选 PIN 保护
public ShareCreateResponse create(long userId, CreateShareRequest req) {
    // 步骤1：加载文件节点，分享者须对该文件有读权限
    FsNode node = loadFileNode(req.getNodeId());
    fsNodeService.requireReadableNode(userId, node);
    // 步骤2：解析有效期（3h/24h/72h → 秒），可选 PIN 做 BCrypt 哈希
    int ttlSeconds = resolveTtlSeconds(req.getTtl());
    String pinHash = encodePinIfPresent(req.getPin());
    // 步骤3：组装载荷 JSON，生成随机分享码写入 Redis 并设置 TTL
    ShareRedisPayload payload = ShareRedisPayload.builder()
            .fileId(node.getId()).pinHash(pinHash).build();
    String code = persistWithUniqueCode(toJson(payload), ttlSeconds);
    // 步骤4：返回分享码与有效秒数给前端展示
    return ShareCreateResponse.builder().code(code).ttlSeconds(ttlSeconds).build();
}

// ========== 4.7.2 解锁分享 ==========
// 源文件: service/impl/ShareLinkServiceImpl.java
// 功能：访客校验 PIN 后换取短期 grant token
public String unlock(String rawCode, String pin) {
    // 步骤1：规范化分享码，从 Redis 读取分享载荷
    String code = normalizeCode(rawCode);
    ShareRedisPayload p = loadPayload(code);
    // 步骤2：校验 PIN 明文与存储的哈希是否匹配
    if (!passwordEncoder.matches(pin, p.getPinHash())) {
        throw new IllegalArgumentException("PIN 错误");
    }
    // 步骤3：签发短期 grant token，Redis 绑定 token→分享码（约 30 分钟）
    String token = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(grantKey(token), code, GRANT_TTL_SECONDS);
    // 步骤4：返回 token，由控制器写入 HttpOnly Cookie 供下载/预览校验
    return token;
}

// ========== 4.8.1 Office 转 PDF ==========
// 源文件: preview/LibreOfficePdfConverter.java
// 功能：调用本机 LibreOffice 将 Office 文档转为 PDF 供预览
public void convertToPdf(Path sourceFile, Path targetPdf) {
    // 步骤1：检查预览开关与 soffice 可执行文件是否存在
    if (!props.isEnabled() || !Files.isRegularFile(props.getSofficePath())) {
        throw new IllegalStateException("LibreOffice 未配置");
    }
    Path tempDir = Files.createTempDirectory("fs-lo-");
    try {
        // 步骤2：无界面调用 LibreOffice 转换命令
        Process process = new ProcessBuilder(
                soffice, "--headless", "--convert-to", "pdf",
                "--outdir", tempDir.toString(), sourceFile.toString()
        ).start();
        // 步骤3：等待进程结束，超时则强杀并提示下载原件
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new OfficePreviewFailedException("转换超时");
        }
        // 步骤4：将生成的 PDF 复制到目标缓存路径
        Files.copy(tempDir.resolve("source.pdf"), targetPdf, REPLACE_EXISTING);
    } finally {
        // 步骤5：清理临时目录
        deleteRecursively(tempDir);
    }
}

// ========== 4.8.2 同名冲突异常 ==========
// 源文件: controller/ApiExceptionHandler.java
// 功能：将同名文件冲突映射为 HTTP 409 结构化响应
@ExceptionHandler(FileNameConflictException.class)
public ResponseEntity<Map<String, Object>> fileNameConflict(FileNameConflictException e) {
    // 步骤1：组装统一 JSON，携带冲突文件 id 与文件名
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage());
    body.put("code", "FILE_NAME_CONFLICT");
    body.put("existingFileId", e.getExistingFileId());
    body.put("fileName", e.getFileName());
    // 步骤2：返回 HTTP 409，前端提示用户选择「替换」或改名
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
}
