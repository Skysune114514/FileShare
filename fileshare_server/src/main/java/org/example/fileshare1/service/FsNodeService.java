package org.example.fileshare1.service;

// 文件树业务接口（全项目最大接口）。
// 实现类 FsNodeServiceImpl 覆盖：个人空间、版本历史、预览、广场、项目成员，
// 所以读接口清单就等于预览了整个“文件系统能做的事”。

import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.dto.FsNodeRevisionVO;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.GalleryProjectVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.dto.UserCandidateVO;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件树业务接口：元数据在库、二进制在配置的存储根目录。
 * <p>实现类 {@link org.example.fileshare1.service.impl.FsNodeServiceImpl} 体量最大，涵盖个人/协作空间、版本快照、广场只读、项目成员、预览等。</p>
 */
public interface FsNodeService {

    /**
     * 校验登录用户可读该节点（本人项目树或作为成员被授权的项目内）。
     */
    void requireReadableNode(long userId, FsNode node);

    List<FsNodeVO> listChildren(long userId, long parentId);

    FsNodeVO createFolder(long userId, long parentId, String name);

    /** 重命名文件夹（含空间根下的一级「项目」） */
    FsNodeVO renameFolder(long userId, long nodeId, String newName);

    FsNodeVO uploadFile(long userId, long parentId, MultipartFile file);

    /**
     * 用新内容替换已有文件：先将当前内容写入历史快照，再覆盖物理文件；快照每文件最多保留 20 条。
     */
    FsNodeVO replaceFileContent(long userId, long nodeId, MultipartFile file);

    /** 列出某文件节点的历史快照（新到旧） */
    List<FsNodeRevisionVO> listFileRevisions(long userId, long nodeId);

    /** 下载某一历史快照 */
    ResponseEntity<Resource> downloadFileRevision(long userId, long nodeId, long revisionId);

    /** 历史快照 Office 转 PDF 预览（与当前文件预览共用 LibreOffice；缓存按 revisionId 区分） */
    ResponseEntity<Resource> previewOfficeAsPdfForRevision(long userId, long nodeId, long revisionId);

    /** 删除单条历史快照（物理文件与预览缓存一并清理） */
    void deleteFileRevision(long userId, long fileId, long revisionId);

    FsNodeVO uploadZipFolder(long userId, long parentId, MultipartFile zipFile);

    /**
     * 浏览器选择本地文件夹上传：{@code files} 与 {@code relativePaths} 按下标一一对应。
     * {@code rootNameHint} 为所选根目录名（浏览器未在路径中带顶层目录时由前端传入）。
     */
    FsNodeVO uploadFolder(long userId, long parentId, List<MultipartFile> files, List<String> relativePaths, String rootNameHint);

    void deleteNode(long userId, long id);

    ResponseEntity<Resource> download(long userId, long id);

    /**
     * 将常见 Office 文档转为 PDF 并以内联方式返回（点预览时转换/读缓存；须登录且对所在项目有读权限）。
     */
    ResponseEntity<Resource> previewOfficeAsPdf(long userId, long fileId);

    /** 将<strong>项目根</strong>及整棵子树设为公开/私有（仅项目管理员；节点须为空间根下的一级文件夹） */
    FsNodeVO setPublicRecursive(long userId, long nodeId, boolean isPublic);

    /**
     * 广场：分页列出所有已公开的项目（根目录下的文件夹）。page 从 1 开始。
     */
    PageResult<GalleryProjectVO> listPublicProjects(int page, int size);

    /** 广场：某用户在某父目录下的公开子节点（parentId=0 为根） */
    List<FsNodeVO> listPublicChildren(long ownerUserId, long parentId);

    /** 广场：下载他人公开文件 */
    ResponseEntity<Resource> downloadPublicFile(long ownerUserId, long fileId);

    /** 广场：公开 Office 文件转 PDF 预览 */
    ResponseEntity<Resource> previewPublicOfficeAsPdf(long ownerUserId, long fileId);

    /**
     * 临时分享：调用方已校验分享载荷与 {@link FsNode} 一致；仅做 Office 可预览检查与 LibreOffice 转 PDF（与本人预览共用缓存路径）。
     */
    ResponseEntity<Resource> previewOfficeAsPdfForSharedFile(FsNode node);

    /**
     * 管理员：对任意<strong>当前处于广场公开</strong>的节点（项目根、子文件夹或文件）整棵子树取消公开。
     */
    FsNodeVO adminUnpublishPublicProject(long adminUserId, long nodeId);

    /** 项目成员或创建者可查看成员列表（只读）。 */
    List<ProjectMemberVO> listProjectMembers(long actorUserId, long projectRootId);

    /** 广场：项目已公开时任何人可查看成员列表（只读）。 */
    List<ProjectMemberVO> listPublicProjectMembers(long projectRootId);

    void addProjectMember(long actorUserId, long projectRootId, long targetUserId, String role);

    void removeProjectMember(long actorUserId, long projectRootId, long targetUserId);

    List<UserCandidateVO> searchProjectMemberCandidates(long actorUserId, long projectRootId, String q);

    /** 有权限时返回 {@code project_admin} 或 {@code member}，否则 {@code null} */
    String getMyProjectRole(long userId, long projectRootId);
}
