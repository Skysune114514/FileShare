package org.example.fileshare1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.config.FileStorageProperties;
import org.example.fileshare1.config.LibreOfficePreviewProperties;
import org.example.fileshare1.dto.FsNodeRevisionVO;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.GalleryProjectVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.dto.UserCandidateVO;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.entity.FsNodeRevision;
import org.example.fileshare1.entity.ProjectMember;
import org.example.fileshare1.entity.User;
import org.example.fileshare1.exception.FileNameConflictException;
import org.example.fileshare1.mapper.FsNodeMapper;
import org.example.fileshare1.mapper.FsNodeRevisionMapper;
import org.example.fileshare1.mapper.ProjectMemberMapper;
import org.example.fileshare1.mapper.UserMapper;
import org.example.fileshare1.preview.LibreOfficePdfConverter;
import org.example.fileshare1.preview.OfficePreviewSupport;
import org.example.fileshare1.security.ProjectRoles;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.example.fileshare1.util.ZipArchiveSupport;

/**
 * <p><b>定位：</b>文件树核心业务。用户空间根下「项目」一级目录；项目内 {@code owner_user_id} 恒为<strong>项目创建者</strong>命名空间，
 * 协作者经 {@code project_member} 鉴权；{@code uploaded_by_name} 为操作者署名快照。</p>
 * <p><b>关联入口：</b>{@link org.example.fileshare1.controller.FsController}（登录态 CRUD）、
 * {@link org.example.fileshare1.controller.GalleryController}（广场只读）、
 * {@link org.example.fileshare1.controller.ProjectMemberController}（成员维护）均委托本类；
 * 物理根目录 {@link org.example.fileshare1.config.FileStorageProperties}；Office→PDF {@link LibreOfficePdfConverter}。</p>
 * <p><b>源码大致分区：</b>启动与成员补全 → 项目协作鉴权 → 列表/新建/上传 → 删除与下载 → 替换与历史快照 → 预览缓存 → 广场公开递归 → 成员 CRUD → 工具方法。</p>
 * <p><b>阅读时的两条主线：</b>
 * 1. 权限主线：几乎每个 public 方法第一件事都是 requireProjectRead/Write/Admin；
 * 2. 一致性主线：凡是“先写磁盘再写 DB”的方法，都要登记 deletePhysicalOnRollback；
 *    凡是“先删 DB 再删磁盘”的方法，都要登记 deletePhysicalAfterCommit。</p>
 */
@Service
@RequiredArgsConstructor
public class FsNodeServiceImpl implements FsNodeService {

    // 日志：物理清理失败只警告，不能影响主流程。
    private static final Logger log = LoggerFactory.getLogger(FsNodeServiceImpl.class);

    // 每个文件最多保留多少条历史快照，超出后裁最旧的。
    private static final int MAX_FILE_REVISIONS = 20;
    /** 单次「上传文件夹」允许的最大文件数 */
    private static final int MAX_FOLDER_UPLOAD_FILES = 400;
    /**
     * 上传黑名单：可直接执行/常用于投递脚本的扩展名一律禁止上传，
     * 文件夹上传与 ZIP 内包含同名文件时也拒绝整批，避免把可执行内容引入共享存储。
     */
    private static final java.util.Set<String> BLOCKED_UPLOAD_EXTENSIONS = java.util.Set.of(
            ".exe", ".dll", ".com", ".scr", ".msi",
            ".bat", ".cmd", ".ps1", ".psm1", ".vbs", ".vbe", ".js", ".jsp", ".php", ".sh",
            ".jar", ".class", ".reg", ".hta"
    );

    // 四张业务表 Mapper：fs_node=文件树；revision=历史；project_member=成员；user=用户。
    private final FsNodeMapper fsNodeMapper;
    private final FsNodeRevisionMapper fsNodeRevisionMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    // 磁盘存储根：所有 storageKey 都相对它解析。
    private final FileStorageProperties storageProperties;
    // LibreOffice 预览开关/上限/超时配置。
    private final LibreOfficePreviewProperties libreOfficePreviewProperties;
    // 真正的 soffice 子进程转换器。
    private final LibreOfficePdfConverter libreOfficePdfConverter;

    // 当前文件转 PDF 的 JVM 内互斥锁：key=fileId。
    private final ConcurrentHashMap<Long, Object> officePreviewLocks = new ConcurrentHashMap<>();
    /** 历史快照 Office 转 PDF 的互斥锁（key = revisionId） */
    private final ConcurrentHashMap<Long, Object> revisionOfficePreviewLocks = new ConcurrentHashMap<>();

    /**
     * 应用启动：确保磁盘存储目录存在；为历史一级项目根补全创建者在 {@code project_member} 中的管理员行。
     */
    @PostConstruct
    void initStorageAndMembers() throws IOException {
        Files.createDirectories(storageProperties.getRoot());
        backfillProjectOwnerAdmins();
    }

    /** 扫描所有空间根下的一级项目文件夹，为尚未有成员行的创建者插入 {@code project_admin}。 */
    private void backfillProjectOwnerAdmins() {
        List<FsNode> roots = fsNodeMapper.selectList(new LambdaQueryWrapper<FsNode>()
                .eq(FsNode::getParentId, FsNode.ROOT_PARENT_ID)
                .eq(FsNode::getNodeType, FsNode.TYPE_FOLDER));
        for (FsNode root : roots) {
            ensureProjectAdminMembership(root.getId(), root.getOwnerUserId());
        }
    }

    /** 若尚无记录则为指定用户在指定项目根插入一条管理员成员（幂等）。 */
    private void ensureProjectAdminMembership(long projectRootId, long memberUserId) {
        long cnt = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId)
                .eq(ProjectMember::getUserId, memberUserId));
        if (cnt > 0) {
            return;
        }
        ProjectMember pm = new ProjectMember();
        pm.setProjectRootNodeId(projectRootId);
        pm.setUserId(memberUserId);
        pm.setRole(ProjectRoles.PROJECT_ADMIN);
        pm.setCreatedAt(LocalDateTime.now());
        projectMemberMapper.insert(pm);
    }

    /** 加载用户实体；不存在则抛业务异常（各接口前置校验）。 */
    private User requireUser(long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return u;
    }

    /** 当前用户在指定「项目根 id」下的权限档位（与平台 admin 角色正交）。 */
    private enum ProjectAccess {
        NONE, READ, WRITE, ADMIN
    }

    /**
     * 从任意树内节点（文件或文件夹）向上找到空间根下的一级「项目」根文件夹。
     * 起点可以是 {@link FsNode#TYPE_FILE}，仅要求沿父链均为文件夹直至根。
     */
    private FsNode walkToProjectRoot(FsNode node) {
        FsNode cur = Objects.requireNonNull(node, "节点不存在");
        int guard = 0;
        // 步骤1：沿 parentId 向上循环，直至 parentId=0（空间根下的一级「项目」）
        while (!Objects.equals(cur.getParentId(), FsNode.ROOT_PARENT_ID)) {
            if (guard++ > 1000) {
                throw new IllegalStateException("目录层级过深");
            }
            FsNode parent = fsNodeMapper.selectById(cur.getParentId());
            if (parent == null) {
                throw new IllegalArgumentException("节点不在有效空间树内");
            }
            if (!FsNode.TYPE_FOLDER.equals(parent.getNodeType())) {
                throw new IllegalArgumentException("路径非法");
            }
            cur = parent;
        }
        if (!FsNode.TYPE_FOLDER.equals(cur.getNodeType())) {
            throw new IllegalArgumentException("项目根须为文件夹");
        }
        return cur;
    }

    /**
     * 计算用户对某项目根目录 id 的访问档位：创建者恒为 ADMIN；否则查 {@code project_member}。
     */
    private ProjectAccess accessInProject(long userId, long projectRootId) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null
                || !FsNode.TYPE_FOLDER.equals(root.getNodeType())
                || !Objects.equals(root.getParentId(), FsNode.ROOT_PARENT_ID)) {
            return ProjectAccess.NONE;
        }
        if (Objects.equals(userId, root.getOwnerUserId())) {
            return ProjectAccess.ADMIN;
        }
        ProjectMember m = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId)
                .eq(ProjectMember::getUserId, userId));
        if (m == null) {
            return ProjectAccess.NONE;
        }
        if (ProjectRoles.isProjectAdmin(m.getRole())) {
            return ProjectAccess.ADMIN;
        }
        return ProjectAccess.WRITE;
    }

    /** 要求对节点所在项目树具备读权限（列表、下载、预览、列历史等）。 */
    private void requireProjectRead(long userId, FsNode anyInTree) {
        FsNode root = walkToProjectRoot(anyInTree);
        ProjectAccess a = accessInProject(userId, root.getId());
        if (a != ProjectAccess.NONE) {
            return;
        }
        throw new IllegalArgumentException("无权访问");
    }

    /** 要求成员或项目管理员（上传、改名、替换文件、删历史快照等写操作）。 */
    private void requireProjectWrite(long userId, FsNode anyInTree) {
        FsNode root = walkToProjectRoot(anyInTree);
        ProjectAccess a = accessInProject(userId, root.getId());
        if (a == ProjectAccess.WRITE || a == ProjectAccess.ADMIN) {
            return;
        }
        throw new IllegalArgumentException("无权修改");
    }

    /** 要求项目管理员或创建者（删节点、项目根设广场公开等）。 */
    private void requireProjectAdmin(long userId, FsNode anyInTree) {
        FsNode root = walkToProjectRoot(anyInTree);
        ProjectAccess a = accessInProject(userId, root.getId());
        if (a == ProjectAccess.ADMIN) {
            return;
        }
        throw new IllegalArgumentException("需要项目管理员权限");
    }

    /** 父目录须存在且当前用户对该父目录所在项目有读权限（用于子列表）。 */
    private void assertParentFolderReadable(long userId, long parentId) {
        if (parentId == FsNode.ROOT_PARENT_ID) {
            return;
        }
        FsNode p = fsNodeMapper.selectById(parentId);
        if (p == null || !FsNode.TYPE_FOLDER.equals(p.getNodeType())) {
            throw new IllegalArgumentException("父目录不存在或无权访问");
        }
        requireProjectRead(userId, p);
    }

    /** 父目录须存在且当前用户对该父目录所在项目有写权限（用于新建、上传）。 */
    private void assertParentFolderWritable(long userId, long parentId) {
        if (parentId == FsNode.ROOT_PARENT_ID) {
            return;
        }
        FsNode p = fsNodeMapper.selectById(parentId);
        if (p == null || !FsNode.TYPE_FOLDER.equals(p.getNodeType())) {
            throw new IllegalArgumentException("父目录不存在或无权访问");
        }
        requireProjectWrite(userId, p);
    }

    /**
     * 新建子节点时应写入的 {@code owner_user_id}：在本人空间根下为本人；在项目内为项目创建者。
     */
    private long namespaceOwnerForParent(long parentId, long actingUserId) {
        if (parentId == FsNode.ROOT_PARENT_ID) {
            return actingUserId;
        }
        FsNode parent = fsNodeMapper.selectById(parentId);
        if (parent == null || !FsNode.TYPE_FOLDER.equals(parent.getNodeType())) {
            throw new IllegalArgumentException("父目录不存在或无权访问");
        }
        return walkToProjectRoot(parent).getOwnerUserId();
    }

    /** {@inheritDoc} 步骤：校验用户存在 → 校验节点所在项目读权限。 */
    @Override
    public void requireReadableNode(long userId, FsNode node) {
        requireUser(userId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        requireProjectRead(userId, node);
    }

    /** {@inheritDoc} 根目录合并「我创建的+参与的」项目；项目内按命名空间列子节点。 */
    @Override
    public List<FsNodeVO> listChildren(long userId, long parentId) {
        requireUser(userId);
        if (parentId == FsNode.ROOT_PARENT_ID) {
            // 步骤2a：空间根 — 查参与项目 + 合并「我创建的」一级文件夹
            List<ProjectMember> memberships = projectMemberMapper.selectList(
                    new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId));
            List<Long> memberRootIds = memberships.stream()
                    .map(ProjectMember::getProjectRootNodeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
            q.eq(FsNode::getParentId, FsNode.ROOT_PARENT_ID)
                    .eq(FsNode::getNodeType, FsNode.TYPE_FOLDER)
                    .and(w -> {
                        w.eq(FsNode::getOwnerUserId, userId);
                        if (!memberRootIds.isEmpty()) {
                            w.or().in(FsNode::getId, memberRootIds);
                        }
                    })
                    .last("ORDER BY (CASE node_type WHEN 'FOLDER' THEN 0 ELSE 1 END), name ASC");
            return fsNodeMapper.selectList(q).stream().map(n -> {
                FsNodeVO vo = FsNodeVO.from(n);
                vo.setProjectMemberNames(buildProjectMemberNamesSummary(n.getId()));
                return vo;
            }).toList();
        }
        // 步骤2b：项目内 — 校验父目录读权限后按命名空间 owner 列子节点
        assertParentFolderReadable(userId, parentId);
        FsNode parent = fsNodeMapper.selectById(parentId);
        long nsOwner = walkToProjectRoot(Objects.requireNonNull(parent)).getOwnerUserId();
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, parentId)
                .eq(FsNode::getOwnerUserId, nsOwner)
                .last("ORDER BY (CASE node_type WHEN 'FOLDER' THEN 0 ELSE 1 END), name ASC");
        return fsNodeMapper.selectList(q).stream().map(FsNodeVO::from).toList();
    }

    /** {@inheritDoc} 新建文件夹；在空间根下创建时自动为创建者插入 project_admin 成员行。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO createFolder(long userId, long parentId, String name) {
        User u = requireUser(userId);
        assertParentFolderWritable(userId, parentId);
        String safe = sanitizeName(name);
        long nsOwner = namespaceOwnerForParent(parentId, userId);
        assertNameAvailable(nsOwner, parentId, safe);
        FsNode folder = new FsNode();
        folder.setParentId(parentId);
        folder.setName(safe);
        folder.setNodeType(FsNode.TYPE_FOLDER);
        folder.setSizeBytes(0L);
        folder.setOwnerUserId(nsOwner);
        folder.setUploadedByName(snapshotName(u));
        folder.setUploadedByUserId(userId);
        folder.setIsPublic(inheritPublicFromParentFolder(parentId));
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        try {
            fsNodeMapper.insert(folder);
        } catch (DuplicateKeyException e) {
            // 并发场景：先查后插存在时间窗，唯一键兜底并转成同一句用户可读提示
            throw new IllegalArgumentException("同目录下已存在同名: " + safe);
        }
        if (parentId == FsNode.ROOT_PARENT_ID) {
            ensureProjectAdminMembership(folder.getId(), userId);
        }
        return FsNodeVO.from(folder);
    }

    /** {@inheritDoc} 项目根重命名须管理员，子文件夹须写权限。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO renameFolder(long userId, long nodeId, String newName) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        if (!FsNode.TYPE_FOLDER.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能重命名文件夹");
        }
        if (Objects.equals(node.getParentId(), FsNode.ROOT_PARENT_ID)) {
            requireProjectAdmin(userId, node);
        } else {
            requireProjectWrite(userId, node);
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String safe = sanitizeName(newName);
        if (safe.equals(node.getName())) {
            return FsNodeVO.from(node);
        }
        long nsOwner = node.getOwnerUserId();
        assertSiblingNameFreeExcept(nsOwner, node.getParentId(), safe, nodeId);
        node.setName(safe);
        node.setUpdatedAt(LocalDateTime.now());
        fsNodeMapper.updateById(node);
        return FsNodeVO.from(Objects.requireNonNull(fsNodeMapper.selectById(nodeId)));
    }

    /** {@inheritDoc} 单文件上传：写权限 → 同名检查 → 落盘 → 插入 fs_node。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO uploadFile(long userId, long parentId, MultipartFile file) {
        User u = requireUser(userId);
        // 步骤1：禁止在空间根直接上传
        if (parentId == FsNode.ROOT_PARENT_ID) {
            throw new IllegalArgumentException("请先在空间根目录「新建项目」，进入项目后再上传文件");
        }
        // 步骤2：父目录写权限
        assertParentFolderWritable(userId, parentId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        // 步骤3：清洗文件名 + 同名冲突检查（409）
        String safe = sanitizeName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed");
        assertAllowedUploadName(safe);
        assertChildNameForNewUpload(userId, parentId, safe);
        // 步骤4：生成 storageKey 并 transferTo 磁盘
        String storageKey = newStorageKey(safe);
        Path absolute = storageProperties.getRoot().resolve(storageKey);
        try {
            Files.createDirectories(absolute.getParent());
            file.transferTo(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("保存文件失败: " + e.getMessage(), e);
        }
        // 落盘成功后登记：本事务若回滚（典型是插库失败/后续冲突），自动删除刚写入的物理文件
        deletePhysicalOnRollback(absolute);
        // 步骤5：insert fs_node 元数据
        long nsOwner = namespaceOwnerForParent(parentId, userId);
        FsNode node = new FsNode();
        node.setParentId(parentId);
        node.setName(safe);
        node.setNodeType(FsNode.TYPE_FILE);
        node.setSizeBytes(file.getSize());
        node.setStorageKey(storageKey);
        node.setContentType(file.getContentType());
        node.setOwnerUserId(nsOwner);
        node.setUploadedByName(snapshotName(u));
        node.setUploadedByUserId(userId);
        node.setIsPublic(inheritPublicFromParentFolder(parentId));
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        try {
            fsNodeMapper.insert(node);
        } catch (DuplicateKeyException e) {
            // 并发同名上传：预检查之后仍可能撞 uk_owner_parent_name；查回已提交的胜者并转 409
            FsNode existing = findChildNode(nsOwner, parentId, safe);
            if (existing != null && FsNode.TYPE_FILE.equals(existing.getNodeType())) {
                throw new FileNameConflictException(existing.getId(), safe);
            }
            if (existing != null && FsNode.TYPE_FOLDER.equals(existing.getNodeType())) {
                throw new IllegalArgumentException("同目录下已存在同名文件夹: " + safe);
            }
            throw new IllegalStateException("保存文件元数据失败", e);
        }
        return FsNodeVO.from(node);
    }

    /** {@inheritDoc} ZIP 解压为文件夹树并递归落库（防路径穿越）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO uploadZipFolder(long userId, long parentId, MultipartFile zipFile) {
        User u = requireUser(userId);
        assertParentFolderWritable(userId, parentId);
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("ZIP 不能为空");
        }
        String original = zipFile.getOriginalFilename() != null ? zipFile.getOriginalFilename() : "archive.zip";
        if (!original.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("请上传 .zip 文件");
        }
        String baseName = sanitizeName(original.substring(0, original.length() - 4));
        long nsOwner = namespaceOwnerForParent(parentId, userId);
        assertNameAvailable(nsOwner, parentId, baseName);
        FsNode rootFolder = insertFolderRecord(userId, nsOwner, snapshotName(u), parentId, baseName);

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("fs-zip-");
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败", e);
        }
        try {
            ZipArchiveSupport.unzipStreamToDirectory(zipFile.getInputStream(), tempDir);
            persistTreeFromDisk(userId, nsOwner, snapshotName(u), rootFolder.getId(), tempDir);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("解压失败: " + e.getMessage(), e);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
        return FsNodeVO.from(rootFolder);
    }

    /** {@inheritDoc} 浏览器多文件按相对路径还原目录树（上限 {@value #MAX_FOLDER_UPLOAD_FILES} 个文件）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO uploadFolder(long userId, long parentId, List<MultipartFile> files, List<String> relativePaths,
                                 String rootNameHint) {
        User u = requireUser(userId);
        if (parentId == FsNode.ROOT_PARENT_ID) {
            throw new IllegalArgumentException("请先在空间根目录「新建项目」，进入项目后再上传文件夹");
        }
        assertParentFolderWritable(userId, parentId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("未选择任何文件");
        }
        if (relativePaths == null || relativePaths.size() != files.size()) {
            throw new IllegalArgumentException("文件与路径列表数量不一致");
        }
        if (files.size() > MAX_FOLDER_UPLOAD_FILES) {
            throw new IllegalArgumentException("单次最多上传 " + MAX_FOLDER_UPLOAD_FILES + " 个文件，请分批或改用 ZIP");
        }
        long nsOwner = namespaceOwnerForParent(parentId, userId);
        String uploaderName = snapshotName(u);

        FolderUploadPlan plan = buildFolderUploadPlan(files, relativePaths, rootNameHint);
        if (plan.entries().isEmpty()) {
            throw new IllegalArgumentException("未收到有效文件内容，请确认文件夹内含有文件后重试");
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("fs-folder-");
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败", e);
        }
        try {
            int written = 0;
            for (FolderUploadEntry entry : plan.entries()) {
                MultipartFile mf = entry.file();
                if (mf == null || mf.isEmpty()) {
                    continue;
                }
                Path target = resolvePathUnder(tempDir, entry.normalizedPath());
                Files.createDirectories(target.getParent());
                try (var in = mf.getInputStream()) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
            if (written == 0) {
                throw new IllegalArgumentException("所有文件均未写入成功，请重试或改用 ZIP 上传");
            }
            Path rootOnDisk = tempDir.resolve(plan.rootFolderName());
            if (!Files.isDirectory(rootOnDisk)) {
                throw new IllegalArgumentException("未找到根文件夹: " + plan.rootFolderName());
            }
            assertNameAvailable(nsOwner, parentId, plan.rootFolderName());
            FsNode rootFolder = insertFolderRecord(userId, nsOwner, uploaderName, parentId, plan.rootFolderName());
            persistTreeFromDisk(userId, nsOwner, uploaderName, rootFolder.getId(), rootOnDisk);
            return FsNodeVO.from(Objects.requireNonNull(fsNodeMapper.selectById(rootFolder.getId())));
        } catch (IOException e) {
            throw new IllegalStateException("上传文件夹失败: " + e.getMessage(), e);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private record FolderUploadEntry(MultipartFile file, String normalizedPath) {
    }

    private record FolderUploadPlan(String rootFolderName, List<FolderUploadEntry> entries) {
    }

    /**
     * 统一路径形态为 {@code 根目录名/.../文件名}。
     * 浏览器有时只返回 {@code a.txt}（相对所选目录），须用 {@code rootNameHint} 补上顶层目录名。
     */
    private FolderUploadPlan buildFolderUploadPlan(List<MultipartFile> files, List<String> relativePaths,
                                                   String rootNameHint) {
        String rootFolderName = null;
        List<FolderUploadEntry> raw = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile mf = files.get(i);
            if (mf == null || mf.isEmpty()) {
                continue;
            }
            String norm = normalizeRelativePath(relativePaths.get(i));
            String[] segs = splitPathSegments(norm);
            if (segs.length == 0) {
                continue;
            }
            if (segs.length >= 2) {
                String root = sanitizeName(segs[0]);
                if (rootFolderName == null) {
                    rootFolderName = root;
                } else if (!rootFolderName.equals(root)) {
                    throw new IllegalArgumentException("一次只能上传一个根文件夹");
                }
                raw.add(new FolderUploadEntry(mf, norm));
            } else {
                raw.add(new FolderUploadEntry(mf, segs[0]));
            }
        }

        if (raw.isEmpty()) {
            return new FolderUploadPlan("", List.of());
        }

        if (rootFolderName == null) {
            if (rootNameHint != null && !rootNameHint.isBlank()) {
                rootFolderName = sanitizeName(rootNameHint.trim());
            } else {
                rootFolderName = sanitizeName("上传文件夹");
            }
        }

        List<FolderUploadEntry> entries = new ArrayList<>();
        for (FolderUploadEntry e : raw) {
            String path = e.normalizedPath();
            if (path.contains("/")) {
                entries.add(e);
            } else {
                entries.add(new FolderUploadEntry(e.file(), rootFolderName + "/" + path));
            }
        }
        return new FolderUploadPlan(rootFolderName, entries);
    }

    /** 将解压后的目录树写入库与磁盘（ZIP 导入）；节点归属 {@code namespaceOwnerId}，署名为 {@code uploaderName}。 */
    private void persistTreeFromDisk(long actingUserId, long namespaceOwnerId, String uploaderName, long parentId, Path dir) throws IOException {
        List<Path> children = Files.list(dir).sorted(Comparator.comparing(p -> Files.isDirectory(p) ? 0 : 1))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        for (Path child : children) {
            String nm = sanitizeName(child.getFileName().toString());
            if (Files.isDirectory(child)) {
                FsNode f = insertFolderRecord(actingUserId, namespaceOwnerId, uploaderName, parentId, nm);
                persistTreeFromDisk(actingUserId, namespaceOwnerId, uploaderName, f.getId(), child);
            } else {
                assertAllowedUploadName(nm);
                assertNameAvailable(namespaceOwnerId, parentId, nm);
                String storageKey = newStorageKey(nm);
                Path dest = storageProperties.getRoot().resolve(storageKey);
                Files.createDirectories(dest.getParent());
                Files.copy(child, dest);
                // ZIP/文件夹内含重复或并发同名文件时，本事务回滚则删除该副本
                deletePhysicalOnRollback(dest);
                long size = Files.size(dest);
                FsNode fileNode = new FsNode();
                fileNode.setParentId(parentId);
                fileNode.setName(nm);
                fileNode.setNodeType(FsNode.TYPE_FILE);
                fileNode.setSizeBytes(size);
                fileNode.setStorageKey(storageKey);
                String probed = Files.probeContentType(dest);
                fileNode.setContentType(probed);
                fileNode.setOwnerUserId(namespaceOwnerId);
                fileNode.setUploadedByName(uploaderName);
                fileNode.setUploadedByUserId(actingUserId);
                fileNode.setIsPublic(inheritPublicFromParentFolder(parentId));
                fileNode.setCreatedAt(LocalDateTime.now());
                fileNode.setUpdatedAt(LocalDateTime.now());
                try {
                    fsNodeMapper.insert(fileNode);
                } catch (DuplicateKeyException e) {
                    FsNode existing = findChildNode(namespaceOwnerId, parentId, nm);
                    if (existing != null && FsNode.TYPE_FILE.equals(existing.getNodeType())) {
                        throw new FileNameConflictException(existing.getId(), nm);
                    }
                    if (existing != null && FsNode.TYPE_FOLDER.equals(existing.getNodeType())) {
                        throw new IllegalArgumentException("同目录下已存在同名文件夹: " + nm);
                    }
                    throw new IllegalStateException("保存文件元数据失败", e);
                }
            }
        }
    }

    /** 在父目录下插入文件夹元数据（不写物理文件）；用于 ZIP 根目录及子目录。 */
    private FsNode insertFolderRecord(long actingUserId, long namespaceOwnerId, String uploaderName, long parentId, String name) {
        assertNameAvailable(namespaceOwnerId, parentId, name);
        FsNode folder = new FsNode();
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setNodeType(FsNode.TYPE_FOLDER);
        folder.setSizeBytes(0L);
        folder.setOwnerUserId(namespaceOwnerId);
        folder.setUploadedByName(uploaderName);
        folder.setUploadedByUserId(actingUserId);
        folder.setIsPublic(inheritPublicFromParentFolder(parentId));
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        try {
            fsNodeMapper.insert(folder);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("同目录下已存在同名: " + name);
        }
        return folder;
    }

    /**
     * 在已公开的项目下新增子文件夹/文件时，继承父文件夹的 {@code isPublic}，
     * 否则广场 {@code listPublicChildren} 无法列出这些节点（设计为每条记录单独带公开标记）。
     */
    private boolean inheritPublicFromParentFolder(long parentId) {
        if (parentId == FsNode.ROOT_PARENT_ID) {
            return false;
        }
        FsNode parent = fsNodeMapper.selectById(parentId);
        if (parent == null || !FsNode.TYPE_FOLDER.equals(parent.getNodeType())) {
            return false;
        }
        return Boolean.TRUE.equals(parent.getIsPublic());
    }

    /**
     * {@inheritDoc}
     * 事务内只删数据库行；全部行删成功后，物理文件/预览缓存的删除推迟到事务提交后执行。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(long userId, long id) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(id);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        assertCanDeleteNode(userId, node);
        List<Path> physicalCleanup = new ArrayList<>();
        deleteSubtreeRecursively(id, physicalCleanup);
        for (Path p : physicalCleanup) {
            deletePhysicalAfterCommit(p);
        }
    }

    /**
     * 删除权限：文件夹（含项目根）仅项目管理员；文件可由管理员删除任意文件，
     * 或由普通成员删除本人上传的文件。新数据按 {@link FsNode#getUploadedByUserId} 判断；
     * 老数据该列为空时退化为昵称比对，避免老用户无法删除历史文件。
     */
    private void assertCanDeleteNode(long userId, FsNode node) {
        if (FsNode.TYPE_FOLDER.equals(node.getNodeType())) {
            requireProjectAdmin(userId, node);
            return;
        }
        if (!FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("无法删除该类型节点");
        }
        FsNode root = walkToProjectRoot(node);
        ProjectAccess access = accessInProject(userId, root.getId());
        if (access == ProjectAccess.ADMIN) {
            return;
        }
        if (access == ProjectAccess.WRITE) {
            boolean mine = Objects.equals(node.getUploadedByUserId(), userId);
            if (!mine && node.getUploadedByUserId() == null) {
                // 老数据没有上传者 id：昵称快照是当时唯一的可比对线索
                User actor = requireUser(userId);
                mine = snapshotName(actor).equals(node.getUploadedByName());
            }
            if (mine) {
                return;
            }
        }
        throw new IllegalArgumentException("无权删除：仅项目管理员可删文件夹，成员仅可删除本人上传的文件");
    }

    /** 项目成员展示名，按姓名排序后以英文逗号拼接。 */
    private String buildProjectMemberNamesSummary(long projectRootId) {
        List<ProjectMember> rows = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId));
        List<String> names = new ArrayList<>();
        for (ProjectMember m : rows) {
            User u = userMapper.selectById(m.getUserId());
            names.add(ProjectMemberVO.from(m, u).getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(",", names);
    }

    /**
     * 递归删除子树（只删数据库行，先子后父）。
     * 文件节点的历史快照行、当前物理文件路径、预览缓存路径收集进 {@code physicalCleanup}，
     * 由调用方在事务提交成功后统一清理，保证 DB 与磁盘不会因回滚而撕裂。
     */
    private void deleteSubtreeRecursively(long id, List<Path> physicalCleanup) {
        FsNode node = fsNodeMapper.selectById(id);
        if (node == null) {
            return;
        }
        // 步骤1：先递归删除所有子节点（后序：先子后父），保持树删除语义
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, id);
        List<FsNode> children = new ArrayList<>(fsNodeMapper.selectList(q));
        for (FsNode c : children) {
            deleteSubtreeRecursively(c.getId(), physicalCleanup);
        }
        node = fsNodeMapper.selectById(id);
        // 步骤2：文件节点 — 先删历史快照行，物理路径收集起来稍后统一清理
        if (node != null && FsNode.TYPE_FILE.equals(node.getNodeType())) {
            deleteRevisionRowsCollectingCleanup(node, physicalCleanup);
            physicalCleanup.add(cachedPreviewPdfPath(node));
            if (node.getStorageKey() != null && !node.getStorageKey().isBlank()) {
                physicalCleanup.add(storageProperties.getRoot().resolve(node.getStorageKey()));
            }
            forgetPreviewLockAfterCommit(node.getId());
        }
        // 步骤3：deleteById 当前节点
        fsNodeMapper.deleteById(id);
    }

    /** {@inheritDoc} 登录态下载：读权限 → 读磁盘 → attachment 响应。 */
    @Override
    public ResponseEntity<Resource> download(long userId, long id) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(id);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能下载文件");
        }
        requireProjectRead(userId, node);
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

    /**
     * {@inheritDoc}
     * 实现采用“新文件走全新 storage_key + 提交后清理”的 Copy-on-Write 策略：
     * <ol>
     *   <li>新内容先落到 staging，再移到新的存储键，绝不在事务内覆盖旧文件；</li>
     *   <li>旧文件原样复制成 .hist 快照并插行；</li>
     *   <li>DB 把当前节点切到新 storage_key；</li>
     *   <li>事务回滚 → 删除本次新建的两个物理文件，旧文件与旧行原样保留；</li>
     *   <li>事务提交 → 才删除旧物理文件与旧 PDF 预览缓存。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO replaceFileContent(long userId, long nodeId, MultipartFile file) {
        User u = requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能替换文件节点");
        }
        requireProjectWrite(userId, node);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        Path root = storageProperties.getRoot();
        if (node.getStorageKey() == null || node.getStorageKey().isBlank()) {
            throw new IllegalStateException("文件无存储路径");
        }
        Path oldPath = root.resolve(node.getStorageKey());
        if (!Files.isRegularFile(oldPath)) {
            throw new IllegalStateException("物理文件缺失");
        }
        String newLabel = sanitizeName(file.getOriginalFilename() != null ? file.getOriginalFilename() : node.getName());
        assertAllowedUploadName(newLabel);
        if (!newLabel.equals(node.getName())) {
            assertSiblingNameFreeExcept(node.getOwnerUserId(), node.getParentId(), newLabel, nodeId);
        }

        Path stagingDir = root.resolve(".staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败", e);
        }
        Path tempNew = stagingDir.resolve(UUID.randomUUID() + ".part");

        // 本次替换要落盘的两个新文件：新内容 + 旧内容历史快照
        String newKey = newStorageKey(newLabel);
        Path newAbs = root.resolve(newKey);
        String revKey = newStorageKey(node.getName() + ".hist");
        Path revAbs = root.resolve(revKey);

        try {
            file.transferTo(tempNew);
            Files.createDirectories(newAbs.getParent());
            // 移动而非覆盖：新内容成为独立文件，旧文件保持原路径直到事务提交
            Files.move(tempNew, newAbs, StandardCopyOption.REPLACE_EXISTING);

            Files.createDirectories(revAbs.getParent());
            Files.copy(oldPath, revAbs, StandardCopyOption.REPLACE_EXISTING);

            // 一旦登记：本事务回滚时删除这两个新文件，旧文件不受影响
            deletePhysicalOnRollback(newAbs);
            deletePhysicalOnRollback(revAbs);
        } catch (IOException e) {
            deleteFileQuietly(newAbs);
            deleteFileQuietly(revAbs);
            throw new IllegalStateException("替换文件失败: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(tempNew);
            } catch (IOException ignored) {
            }
        }

        FsNodeRevision rev = new FsNodeRevision();
        rev.setNodeId(nodeId);
        rev.setStorageKey(revKey);
        rev.setFileNameSnapshot(node.getName());
        rev.setSizeBytes(node.getSizeBytes());
        rev.setContentType(node.getContentType());
        rev.setCreatedByUserId(userId);
        rev.setCreatedByName(snapshotName(u));
        rev.setCreatedAt(LocalDateTime.now());
        fsNodeRevisionMapper.insert(rev);

        long newSize;
        try {
            newSize = Files.size(newAbs);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件大小失败", e);
        }
        String probedCt = file.getContentType();
        if (probedCt == null || probedCt.isBlank()) {
            try {
                probedCt = Files.probeContentType(newAbs);
            } catch (IOException ignored) {
                probedCt = null;
            }
        }
        node.setName(newLabel);
        node.setSizeBytes(newSize);
        node.setContentType(probedCt);
        node.setStorageKey(newKey);
        node.setUpdatedAt(LocalDateTime.now());
        fsNodeMapper.updateById(node);
        // 提交后：旧物理文件已无任何 DB 行引用；按 nodeId 缓存的旧 PDF 也必须失效
        deletePhysicalAfterCommit(oldPath);
        deletePhysicalAfterCommit(cachedPreviewPdfPath(node));
        pruneOldestRevisions(nodeId);
        return FsNodeVO.from(Objects.requireNonNull(fsNodeMapper.selectById(nodeId)));
    }

    /** {@inheritDoc} 读权限下列出文件历史快照（新到旧）。 */
    @Override
    public List<FsNodeRevisionVO> listFileRevisions(long userId, long nodeId) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能查询文件节点");
        }
        requireProjectRead(userId, node);
        LambdaQueryWrapper<FsNodeRevision> rq = new LambdaQueryWrapper<>();
        rq.eq(FsNodeRevision::getNodeId, nodeId).orderByDesc(FsNodeRevision::getId);
        return fsNodeRevisionMapper.selectList(rq).stream().map(FsNodeRevisionVO::from).toList();
    }

    /** {@inheritDoc} 下载指定历史快照文件。 */
    @Override
    public ResponseEntity<Resource> downloadFileRevision(long userId, long nodeId, long revisionId) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能下载文件节点");
        }
        requireProjectRead(userId, node);
        FsNodeRevision rev = fsNodeRevisionMapper.selectById(revisionId);
        if (rev == null || !Objects.equals(rev.getNodeId(), nodeId)) {
            throw new IllegalArgumentException("快照不存在");
        }
        Path path = storageProperties.getRoot().resolve(rev.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("快照物理文件缺失");
        }
        Resource resource = new FileSystemResource(path);
        String mime = rev.getContentType() != null ? rev.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(rev.getFileNameSnapshot(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(mime))
                .body(resource);
    }

    /** {@inheritDoc} 历史快照 Office 转 PDF（按 revisionId 缓存）。 */
    @Override
    public ResponseEntity<Resource> previewOfficeAsPdfForRevision(long userId, long nodeId, long revisionId) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能预览文件");
        }
        requireProjectRead(userId, node);
        FsNodeRevision rev = fsNodeRevisionMapper.selectById(revisionId);
        if (rev == null || !Objects.equals(rev.getNodeId(), nodeId)) {
            throw new IllegalArgumentException("快照不存在");
        }
        assertOfficePreviewableForRevision(rev);
        Path source = storageProperties.getRoot().resolve(rev.getStorageKey());
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("快照物理文件缺失");
        }
        return buildOrConvertOfficePdfForRevision(node, rev, source);
    }

    /**
     * {@inheritDoc}
     * 先删历史快照行，再登记事务提交后的物理文件/预览缓存清理，
     * 避免“行已删但 DB 回滚、物理文件却已消失”的撕裂。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFileRevision(long userId, long fileId, long revisionId) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(fileId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能删除文件的历史快照");
        }
        requireProjectWrite(userId, node);
        FsNodeRevision rev = fsNodeRevisionMapper.selectById(revisionId);
        if (rev == null || !Objects.equals(rev.getNodeId(), fileId)) {
            throw new IllegalArgumentException("快照不存在");
        }
        fsNodeRevisionMapper.deleteById(revisionId);
        if (rev.getStorageKey() != null && !rev.getStorageKey().isBlank()) {
            deletePhysicalAfterCommit(storageProperties.getRoot().resolve(rev.getStorageKey()));
        }
        deletePhysicalAfterCommit(cachedRevisionPreviewPdfPath(node.getOwnerUserId(), revisionId));
        forgetRevisionLockAfterCommit(revisionId);
    }

    /** 上传新文件前：同 (namespace, 父, 名) 若已存在文件夹则报错；若已存在文件则抛 {@link FileNameConflictException} 供前端引导替换。 */
    private void assertChildNameForNewUpload(long userId, long parentId, String safeName) {
        long ns = namespaceOwnerForParent(parentId, userId);
        FsNode existing = findChildNode(ns, parentId, safeName);
        if (existing == null) {
            return;
        }
        if (FsNode.TYPE_FOLDER.equals(existing.getNodeType())) {
            throw new IllegalArgumentException("同目录下已存在同名文件夹: " + safeName);
        }
        throw new FileNameConflictException(existing.getId(), safeName);
    }

    /** 在指定命名空间 owner 下按父 id 与文件名查找单个子节点（可能为 null）。 */
    private FsNode findChildNode(long namespaceOwnerUserId, long parentId, String name) {
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, parentId)
                .eq(FsNode::getOwnerUserId, namespaceOwnerUserId)
                .eq(FsNode::getName, name);
        return fsNodeMapper.selectOne(q);
    }

    /** 重命名或替换时：排除自身节点 id 后，同父同命名空间下不得与其它节点重名。 */
    private void assertSiblingNameFreeExcept(long namespaceOwnerUserId, long parentId, String name, long excludeNodeId) {
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, parentId)
                .eq(FsNode::getOwnerUserId, namespaceOwnerUserId)
                .eq(FsNode::getName, name)
                .ne(FsNode::getId, excludeNodeId);
        if (fsNodeMapper.selectCount(q) > 0) {
            throw new IllegalArgumentException("同目录下已存在同名: " + name);
        }
    }

    /**
     * 删除文件节点时，把该节点全部历史快照行删掉，
     * 并将每个快照的物理文件与按 revisionId 生成的预览缓存路径收进 {@code cleanup}。
     * 物理清理必须在整个删除事务提交成功后执行。
     */
    private void deleteRevisionRowsCollectingCleanup(FsNode ownerNode, List<Path> cleanup) {
        List<FsNodeRevision> list = fsNodeRevisionMapper.selectList(
                new LambdaQueryWrapper<FsNodeRevision>().eq(FsNodeRevision::getNodeId, ownerNode.getId()));
        for (FsNodeRevision r : list) {
            if (r.getStorageKey() != null && !r.getStorageKey().isBlank()) {
                cleanup.add(storageProperties.getRoot().resolve(r.getStorageKey()));
            }
            cleanup.add(cachedRevisionPreviewPdfPath(ownerNode.getOwnerUserId(), r.getId()));
            forgetRevisionLockAfterCommit(r.getId());
        }
        fsNodeRevisionMapper.delete(new LambdaQueryWrapper<FsNodeRevision>()
                .eq(FsNodeRevision::getNodeId, ownerNode.getId()));
    }

    /**
     * 快照超过 {@link #MAX_FILE_REVISIONS} 时按 id 升序删最旧若干条。
     * 同样遵循“先删行、提交后清物理文件”，行删失败回滚时文件不受影响。
     */
    private void pruneOldestRevisions(long nodeId) {
        LambdaQueryWrapper<FsNodeRevision> q = new LambdaQueryWrapper<>();
        q.eq(FsNodeRevision::getNodeId, nodeId).orderByAsc(FsNodeRevision::getId);
        List<FsNodeRevision> all = new ArrayList<>(fsNodeRevisionMapper.selectList(q));
        int overflow = all.size() - MAX_FILE_REVISIONS;
        if (overflow <= 0) {
            return;
        }
        FsNode owner = fsNodeMapper.selectById(nodeId);
        for (int i = 0; i < overflow; i++) {
            FsNodeRevision r = all.get(i);
            fsNodeRevisionMapper.deleteById(r.getId());
            if (r.getStorageKey() != null && !r.getStorageKey().isBlank()) {
                deletePhysicalAfterCommit(storageProperties.getRoot().resolve(r.getStorageKey()));
            }
            if (owner != null) {
                deletePhysicalAfterCommit(cachedRevisionPreviewPdfPath(owner.getOwnerUserId(), r.getId()));
            }
            forgetRevisionLockAfterCommit(r.getId());
        }
    }

    /** {@inheritDoc} 登录态 Office→PDF 预览（读权限 + 缓存）。 */
    @Override
    public ResponseEntity<Resource> previewOfficeAsPdf(long userId, long fileId) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(fileId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能预览文件");
        }
        requireProjectRead(userId, node);
        assertOfficePreviewable(node);
        Path source = storageProperties.getRoot().resolve(node.getStorageKey());
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("物理文件缺失");
        }
        return buildOrConvertOfficePdf(node, source);
    }

    /** {@inheritDoc} 广场匿名预览：须 is_public 且 owner 一致。 */
    @Override
    public ResponseEntity<Resource> previewPublicOfficeAsPdf(long ownerUserId, long fileId) {
        FsNode node = fsNodeMapper.selectById(fileId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能预览文件");
        }
        if (!Objects.equals(node.getOwnerUserId(), ownerUserId) || !Boolean.TRUE.equals(node.getIsPublic())) {
            throw new IllegalArgumentException("文件未公开或不存在");
        }
        assertOfficePreviewable(node);
        Path source = storageProperties.getRoot().resolve(node.getStorageKey());
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("物理文件缺失");
        }
        return buildOrConvertOfficePdf(node, source);
    }

    /** {@inheritDoc} 分享访客预览（调用方已校验 grant 与节点一致）。 */
    @Override
    public ResponseEntity<Resource> previewOfficeAsPdfForSharedFile(FsNode node) {
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能预览文件");
        }
        assertOfficePreviewable(node);
        Path source = storageProperties.getRoot().resolve(node.getStorageKey());
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("物理文件缺失");
        }
        return buildOrConvertOfficePdf(node, source);
    }

    private void assertOfficePreviewable(FsNode node) {
        if (!OfficePreviewSupport.isOfficeDocumentForPreview(node.getName(), node.getContentType())) {
            throw new IllegalArgumentException("该类型不支持 LibreOffice 在线预览");
        }
        Long sz = node.getSizeBytes();
        if (sz != null && sz > libreOfficePreviewProperties.getMaxSourceBytes()) {
            throw new IllegalArgumentException("文件超过 50MB 上限，请下载原件查看");
        }
    }

    private void assertOfficePreviewableForRevision(FsNodeRevision rev) {
        if (!OfficePreviewSupport.isOfficeDocumentForPreview(rev.getFileNameSnapshot(), rev.getContentType())) {
            throw new IllegalArgumentException("该类型不支持 LibreOffice 在线预览");
        }
        Long sz = rev.getSizeBytes();
        if (sz != null && sz > libreOfficePreviewProperties.getMaxSourceBytes()) {
            throw new IllegalArgumentException("文件超过 50MB 上限，请下载原件查看");
        }
    }

    private ResponseEntity<Resource> buildOrConvertOfficePdf(FsNode node, Path source) {
        final long maxBytes = libreOfficePreviewProperties.getMaxSourceBytes();
        final long bytes;
        try {
            bytes = Files.size(source);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件大小: " + e.getMessage(), e);
        }
        if (bytes > maxBytes) {
            throw new IllegalArgumentException("文件超过 50MB 上限，请下载原件查看");
        }
        Path preview = cachedPreviewPdfPath(node);
        Object lock = officePreviewLocks.computeIfAbsent(node.getId(), k -> new Object());
        synchronized (lock) {
            try {
                // 步骤1：缓存 PDF 存在且不比源文件旧 → 直接 inline 返回
                if (Files.isRegularFile(preview)
                        && Files.getLastModifiedTime(preview).toMillis() >= Files.getLastModifiedTime(source).toMillis()) {
                    return inlinePdfResponse(node, preview);
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取缓存失败: " + e.getMessage(), e);
            }
            // 步骤2：未命中缓存 → LibreOffice 子进程转 PDF 落盘 preview-lo/
            libreOfficePdfConverter.convertToPdf(source, preview);
        }
        return inlinePdfResponse(node.getName(), preview);
    }

    private ResponseEntity<Resource> buildOrConvertOfficePdfForRevision(FsNode node, FsNodeRevision rev, Path source) {
        final long maxBytes = libreOfficePreviewProperties.getMaxSourceBytes();
        final long bytes;
        try {
            bytes = Files.size(source);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件大小: " + e.getMessage(), e);
        }
        if (bytes > maxBytes) {
            throw new IllegalArgumentException("文件超过 50MB 上限，请下载原件查看");
        }
        Path preview = cachedRevisionPreviewPdfPath(node.getOwnerUserId(), rev.getId());
        Object lock = revisionOfficePreviewLocks.computeIfAbsent(rev.getId(), k -> new Object());
        synchronized (lock) {
            try {
                if (Files.isRegularFile(preview)
                        && Files.getLastModifiedTime(preview).toMillis() >= Files.getLastModifiedTime(source).toMillis()) {
                    return inlinePdfResponse(rev.getFileNameSnapshot(), preview);
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取缓存失败: " + e.getMessage(), e);
            }
            try {
                Files.createDirectories(preview.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("创建预览缓存目录失败", e);
            }
            libreOfficePdfConverter.convertToPdf(source, preview);
        }
        return inlinePdfResponse(rev.getFileNameSnapshot(), preview);
    }

    private Path cachedRevisionPreviewPdfPath(long ownerUserId, long revisionId) {
        return storageProperties.getRoot()
                .resolve("preview-lo")
                .resolve(String.valueOf(ownerUserId))
                .resolve("rev-" + revisionId + ".pdf");
    }

    private Path cachedPreviewPdfPath(FsNode node) {
        return storageProperties.getRoot()
                .resolve("preview-lo")
                .resolve(String.valueOf(node.getOwnerUserId()))
                .resolve(node.getId() + ".pdf");
    }

    private ResponseEntity<Resource> inlinePdfResponse(FsNode node, Path pdfPath) {
        return inlinePdfResponse(node.getName(), pdfPath);
    }

    private ResponseEntity<Resource> inlinePdfResponse(String originalFileName, Path pdfPath) {
        Resource resource = new FileSystemResource(pdfPath);
        String pdfName = pdfDisplayName(originalFileName);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(pdfName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    private static String pdfDisplayName(String originalName) {
        int dot = originalName.lastIndexOf('.');
        String stem = dot > 0 ? originalName.substring(0, dot) : originalName;
        return stem + ".pdf";
    }

    /** {@inheritDoc} 项目管理员将项目根及子树递归设公开/私有。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO setPublicRecursive(long userId, long nodeId, boolean isPublic) {
        requireUser(userId);
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        requireProjectAdmin(userId, node);
        if (!isProjectRootFolder(node)) {
            throw new IllegalArgumentException("公开/取消公开仅针对空间根目录下的「项目」文件夹");
        }
        applyPublicRecursive(nodeId, isPublic);
        return FsNodeVO.from(Objects.requireNonNull(fsNodeMapper.selectById(nodeId)));
    }

    /** {@inheritDoc} 平台管理员对任意公开节点整棵子树取消公开。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FsNodeVO adminUnpublishPublicProject(long adminUserId, long nodeId) {
        User admin = requireUser(adminUserId);
        if (!UserRoles.isAdmin(admin.getRole())) {
            throw new IllegalArgumentException("需要管理员权限");
        }
        FsNode node = fsNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        if (!Boolean.TRUE.equals(node.getIsPublic())) {
            throw new IllegalArgumentException("该项未处于广场公开状态");
        }
        applyPublicRecursive(nodeId, false);
        return FsNodeVO.from(Objects.requireNonNull(fsNodeMapper.selectById(nodeId)));
    }

    /** 将节点及其整棵子树递归打上相同 {@code isPublic}（广场可见性）。 */
    private void applyPublicRecursive(long id, boolean isPublic) {
        FsNode n = fsNodeMapper.selectById(id);
        if (n == null) {
            return;
        }
        n.setIsPublic(isPublic);
        fsNodeMapper.updateById(n);
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, id);
        for (FsNode c : fsNodeMapper.selectList(q)) {
            applyPublicRecursive(c.getId(), isPublic);
        }
    }

    /** {@inheritDoc} 广场分页：仅 is_public 的一级项目。 */
    @Override
    public PageResult<GalleryProjectVO> listPublicProjects(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, size));
        long total = fsNodeMapper.countPublicRootFolders();
        long offset = (long) (p - 1) * s;
        List<FsNode> rows = fsNodeMapper.selectPublicRootFolders(offset, s);
        List<GalleryProjectVO> items = new ArrayList<>();
        for (FsNode n : rows) {
            User owner = userMapper.selectById(n.getOwnerUserId());
            String ownerLabel = owner != null ? snapshotName(owner) : ("用户" + n.getOwnerUserId());
            items.add(GalleryProjectVO.builder()
                    .id(n.getId())
                    .name(n.getName())
                    .ownerUserId(n.getOwnerUserId())
                    .ownerName(ownerLabel)
                    .uploadedByName(n.getUploadedByName())
                    .createdAt(n.getCreatedAt())
                    .updatedAt(n.getUpdatedAt())
                    .build());
        }
        int totalPages = s > 0 ? (int) ((total + s - 1) / s) : 0;
        return PageResult.<GalleryProjectVO>builder()
                .content(items)
                .totalElements(total)
                .totalPages(totalPages)
                .page(p)
                .size(s)
                .build();
    }

    /** {@inheritDoc} 广场浏览某用户公开目录下的子节点。 */
    @Override
    public List<FsNodeVO> listPublicChildren(long ownerUserId, long parentId) {
        User owner = userMapper.selectById(ownerUserId);
        if (owner == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (parentId != FsNode.ROOT_PARENT_ID) {
            FsNode parent = fsNodeMapper.selectById(parentId);
            if (parent == null
                    || !FsNode.TYPE_FOLDER.equals(parent.getNodeType())
                    || !Objects.equals(parent.getOwnerUserId(), ownerUserId)
                    || !Boolean.TRUE.equals(parent.getIsPublic())) {
                throw new IllegalArgumentException("该目录未公开或不存在");
            }
        }
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getOwnerUserId, ownerUserId)
                .eq(FsNode::getParentId, parentId)
                .eq(FsNode::getIsPublic, true)
                .last("ORDER BY (CASE node_type WHEN 'FOLDER' THEN 0 ELSE 1 END), name ASC");
        return fsNodeMapper.selectList(q).stream().map(FsNodeVO::from).toList();
    }

    /** {@inheritDoc} 广场匿名下载公开文件。 */
    @Override
    public ResponseEntity<Resource> downloadPublicFile(long ownerUserId, long fileId) {
        FsNode node = fsNodeMapper.selectById(fileId);
        if (node == null || !FsNode.TYPE_FILE.equals(node.getNodeType())) {
            throw new IllegalArgumentException("只能下载文件");
        }
        if (!Objects.equals(node.getOwnerUserId(), ownerUserId) || !Boolean.TRUE.equals(node.getIsPublic())) {
            throw new IllegalArgumentException("文件未公开或不存在");
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

    /** 同 (owner_user_id, parent_id, name) 唯一约束下的名称占用检查。 */
    private void assertNameAvailable(long namespaceOwnerUserId, long parentId, String name) {
        LambdaQueryWrapper<FsNode> q = new LambdaQueryWrapper<>();
        q.eq(FsNode::getParentId, parentId)
                .eq(FsNode::getOwnerUserId, namespaceOwnerUserId)
                .eq(FsNode::getName, name);
        if (fsNodeMapper.selectCount(q) > 0) {
            throw new IllegalArgumentException("同目录下已存在同名: " + name);
        }
    }

    /** {@inheritDoc} 项目内成员列表：须读权限。 */
    @Override
    public List<ProjectMemberVO> listProjectMembers(long actorUserId, long projectRootId) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            throw new IllegalArgumentException("项目不存在");
        }
        requireProjectRead(actorUserId, root);
        return buildProjectMemberVoList(root);
    }

    /** {@inheritDoc} 广场只读成员列表：项目须已公开。 */
    @Override
    public List<ProjectMemberVO> listPublicProjectMembers(long projectRootId) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            throw new IllegalArgumentException("项目不存在");
        }
        if (!Boolean.TRUE.equals(root.getIsPublic())) {
            throw new IllegalArgumentException("该项目未公开");
        }
        return buildProjectMemberVoList(root);
    }

    /** 成员表 + 创建者（去重），供只读成员列表展示。 */
    private List<ProjectMemberVO> buildProjectMemberVoList(FsNode root) {
        long projectRootId = root.getId();
        List<ProjectMember> rows = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId));
        List<ProjectMemberVO> out = new ArrayList<>();
        User owner = userMapper.selectById(root.getOwnerUserId());
        if (owner != null) {
            out.add(ProjectMemberVO.builder()
                    .userId(owner.getId())
                    .name(snapshotName(owner))
                    .email(owner.getEmail() != null ? owner.getEmail() : "")
                    .role(ProjectRoles.PROJECT_ADMIN)
                    .build());
        }
        for (ProjectMember m : rows) {
            if (Objects.equals(m.getUserId(), root.getOwnerUserId())) {
                continue;
            }
            User u = userMapper.selectById(m.getUserId());
            out.add(ProjectMemberVO.from(m, u));
        }
        out.sort(Comparator.comparing(ProjectMemberVO::getRole).thenComparing(ProjectMemberVO::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Override
    public void addProjectMember(long actorUserId, long projectRootId, long targetUserId, String role) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            throw new IllegalArgumentException("项目不存在");
        }
        requireProjectAdmin(actorUserId, root);
        if (Objects.equals(targetUserId, root.getOwnerUserId())) {
            throw new IllegalArgumentException("创建者已拥有项目管理员权限");
        }
        User target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        String r = role == null || role.isBlank() ? ProjectRoles.MEMBER : role.trim();
        if (!ProjectRoles.MEMBER.equals(r) && !ProjectRoles.PROJECT_ADMIN.equals(r)) {
            throw new IllegalArgumentException("role 仅支持 member 或 project_admin");
        }
        long exists = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId)
                .eq(ProjectMember::getUserId, targetUserId));
        if (exists > 0) {
            throw new IllegalArgumentException("该用户已是项目成员");
        }
        ProjectMember pm = new ProjectMember();
        pm.setProjectRootNodeId(projectRootId);
        pm.setUserId(targetUserId);
        pm.setRole(r);
        pm.setCreatedAt(LocalDateTime.now());
        projectMemberMapper.insert(pm);
    }

    /** {@inheritDoc} 项目管理员移除成员（不可移除创建者）。 */
    @Override
    public void removeProjectMember(long actorUserId, long projectRootId, long targetUserId) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            throw new IllegalArgumentException("项目不存在");
        }
        requireProjectAdmin(actorUserId, root);
        if (Objects.equals(targetUserId, root.getOwnerUserId())) {
            throw new IllegalArgumentException("不可移除项目创建者");
        }
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectRootNodeId, projectRootId)
                .eq(ProjectMember::getUserId, targetUserId));
    }

    /** {@inheritDoc} 管理员按姓名/邮箱搜索可邀请用户（排除已有成员）。 */
    @Override
    public List<UserCandidateVO> searchProjectMemberCandidates(long actorUserId, long projectRootId, String q) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            throw new IllegalArgumentException("项目不存在");
        }
        requireProjectAdmin(actorUserId, root);
        List<Long> memberIds = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectRootNodeId, projectRootId))
                .stream()
                .map(ProjectMember::getUserId)
                .toList();
        Set<Long> exclude = new HashSet<>(memberIds);
        exclude.add(root.getOwnerUserId());
        LambdaQueryWrapper<User> wq = new LambdaQueryWrapper<>();
        String qq = q == null ? "" : q.trim();
        if (!qq.isEmpty()) {
            wq.and(x -> x.like(User::getName, qq).or().like(User::getEmail, qq));
        }
        wq.last("LIMIT 80");
        List<UserCandidateVO> out = new ArrayList<>();
        for (User u : userMapper.selectList(wq)) {
            if (exclude.contains(u.getId())) {
                continue;
            }
            out.add(UserCandidateVO.from(u));
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }

    /** {@inheritDoc} 返回 project_admin/member，非成员返回 null。 */
    @Override
    public String getMyProjectRole(long userId, long projectRootId) {
        FsNode root = fsNodeMapper.selectById(projectRootId);
        if (root == null || !isProjectRootFolder(root)) {
            return null;
        }
        ProjectAccess a = accessInProject(userId, projectRootId);
        if (a == ProjectAccess.NONE) {
            return null;
        }
        if (a == ProjectAccess.ADMIN) {
            return ProjectRoles.PROJECT_ADMIN;
        }
        return ProjectRoles.MEMBER;
    }

    private static String snapshotName(User u) {
        String n = u.getName();
        return (n != null && !n.isBlank()) ? n.trim() : ("用户" + u.getId());
    }

    /** 空间根目录下的文件夹，即一个「项目」根。 */
    private static boolean isProjectRootFolder(FsNode node) {
        return FsNode.TYPE_FOLDER.equals(node.getNodeType())
                && Objects.equals(node.getParentId(), FsNode.ROOT_PARENT_ID);
    }

    /** 在 {@code base} 下按已规范化的相对路径逐级解析（避免 Windows 上 {@code Path.resolve} 整段字符串异常）。 */
    private static Path resolvePathUnder(Path base, String normalizedRel) {
        Path p = base;
        for (String seg : splitPathSegments(normalizedRel)) {
            p = p.resolve(seg);
        }
        return p;
    }

    private static String normalizeRelativePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("路径无效");
        }
        String p = raw.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/")) {
            throw new IllegalArgumentException("非法路径: " + raw);
        }
        if (p.contains("..")) {
            throw new IllegalArgumentException("路径不能包含 ..");
        }
        return p;
    }

    private static String[] splitPathSegments(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(normalizedPath.split("/"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("名称无效");
        }
        String t = name.trim().replace('\\', '/');
        int i = t.lastIndexOf('/');
        if (i >= 0) {
            t = t.substring(i + 1);
        }
        if (t.isBlank() || t.equals(".") || t.equals("..")) {
            throw new IllegalArgumentException("名称无效");
        }
        return t;
    }

    private String newStorageKey(String originalName) {
        LocalDate d = LocalDate.now();
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0 && dot < originalName.length() - 1) {
            ext = originalName.substring(dot);
        }
        String rel = d.getYear() + "/" + String.format("%02d", d.getMonthValue()) + "/" + String.format("%02d", d.getDayOfMonth())
                + "/" + UUID.randomUUID() + ext;
        return rel.replace("\\", "/");
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /**
     * 上传安全校验：黑名单扩展名直接拒绝。
     * 单个文件上传、文件夹上传、ZIP 内文件都会经过这里，保证任何入口都无法绕过。
     */
    private static void assertAllowedUploadName(String name) {
        String ext = extensionOf(name);
        if (ext != null && BLOCKED_UPLOAD_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("禁止上传可执行/脚本类型文件: " + name);
        }
    }

    private static void deleteFileQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("清理物理文件失败: {}，原因: {}", p, e.getMessage());
        }
    }

    /**
     * 将「物理删除」推迟到事务提交成功后执行。
     * 事务期间只删数据库行；若事务回滚，物理文件仍然存在，行也还在，二者保持一致。
     */
    private void deletePhysicalAfterCommit(Path p) {
        if (p == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteFileQuietly(p);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFileQuietly(p);
            }
        });
    }

    /**
     * 事务回滚时清理本次新建的物理文件。
     * 典型场景：先落盘后插库，插库失败时事务回滚，磁盘上不能留下无主文件。
     */
    private void deletePhysicalOnRollback(Path p) {
        if (p == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteFileQuietly(p);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    deleteFileQuietly(p);
                }
            }
        });
    }

    /** 节点删除提交成功后移除 JVM 内预览锁，防止锁对象长期泄漏。 */
    private void forgetPreviewLockAfterCommit(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            officePreviewLocks.remove(nodeId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                officePreviewLocks.remove(nodeId);
            }
        });
    }

    /** 历史快照删除/裁剪提交成功后，移除按 revisionId 建的预览锁。 */
    private void forgetRevisionLockAfterCommit(Long revisionId) {
        if (revisionId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            revisionOfficePreviewLocks.remove(revisionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                revisionOfficePreviewLocks.remove(revisionId);
            }
        });
    }

    private static void deleteDirectoryQuietly(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }
}
