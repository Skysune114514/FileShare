package org.example.fileshare1.controller;

import lombok.RequiredArgsConstructor;
import org.example.fileshare1.dto.FsNodeRevisionVO;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.SetPublicRequest;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.security.LoginUser;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 登录态文件系统接口（/api/fs），是整个后端最核心的一组 REST。
 *
 * Controller 几乎不做判断，只是：
 * 1. 用 @LoginUser 拿当前用户 id；
 * 2. 从 URL/请求体拆参数；
 * 3. 把参数原样交给 FsNodeService，最后返回 VO。
 *
 * “能不能做”的判断全在 FsNodeService 的 requireProject* 系列方法里。
 */
@RestController
@RequestMapping("/api/fs")
@RequiredArgsConstructor
public class FsController {

    // 文件树业务接口；六个 Controller 里只有它同时被 Fs/Gallery/ProjectMember/Admin 复用。
    private final FsNodeService fsNodeService;

    // 列目录接口：parentId=0 表示空间根，列出项目；否则列出项目内子节点。
    @GetMapping("/nodes")
    public List<FsNodeVO> list(
            @LoginUser long userId,
            @RequestParam(defaultValue = "0") long parentId) {
        return fsNodeService.listChildren(userId, parentId);
    }

    // 新建项目：名字从 JSON body 里取；实际调用 createFolder(parentId=0)。
    @PostMapping("/projects")
    public FsNodeVO createProject(
            @LoginUser long userId,
            @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        return fsNodeService.createFolder(userId, FsNode.ROOT_PARENT_ID, name);
    }

    // 新建子文件夹：从 body 取 parentId（缺省 0）和 name。
    @PostMapping("/folders")
    public FsNodeVO createFolder(
            @LoginUser long userId,
            @RequestBody Map<String, Object> body) {
        long parentId = ((Number) body.getOrDefault("parentId", FsNode.ROOT_PARENT_ID)).longValue();
        String name = (String) body.get("name");
        return fsNodeService.createFolder(userId, parentId, name);
    }

    // 单文件上传：multipart，file 字段是二进制流。
    @PostMapping("/files")
    public FsNodeVO uploadFile(
            @LoginUser long userId,
            @RequestParam long parentId,
            @RequestPart("file") MultipartFile file) {
        return fsNodeService.uploadFile(userId, parentId, file);
    }

    // ZIP 上传：后端解压后还原成目录树。
    @PostMapping("/upload-zip")
    public FsNodeVO uploadZip(
            @LoginUser long userId,
            @RequestParam long parentId,
            @RequestPart("file") MultipartFile file) {
        return fsNodeService.uploadZipFolder(userId, parentId, file);
    }

    // 浏览器文件夹上传：files 与 paths 按下标一一对应；rootName 是浏览器可能缺省的根目录名。
    @PostMapping("/upload-folder")
    public FsNodeVO uploadFolder(
            @LoginUser long userId,
            @RequestParam long parentId,
            @RequestParam(value = "rootName", required = false) String rootName,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths) {
        return fsNodeService.uploadFolder(userId, parentId, files, paths, rootName);
    }

    // 替换文件：覆盖当前内容前先把旧内容存成历史快照。
    @PostMapping("/nodes/{id}/replace")
    public FsNodeVO replaceFile(
            @LoginUser long userId,
            @PathVariable long id,
            @RequestPart("file") MultipartFile file) {
        return fsNodeService.replaceFileContent(userId, id, file);
    }

    // 历史版本列表。
    @GetMapping("/nodes/{id}/revisions")
    public List<FsNodeRevisionVO> listRevisions(@LoginUser long userId, @PathVariable long id) {
        return fsNodeService.listFileRevisions(userId, id);
    }

    // 下载某一版历史。
    @GetMapping("/nodes/{id}/revisions/{revisionId}/download")
    public ResponseEntity<Resource> downloadRevision(
            @LoginUser long userId,
            @PathVariable long id,
            @PathVariable long revisionId) {
        return fsNodeService.downloadFileRevision(userId, id, revisionId);
    }

    // 历史版本的 Office 预览（单独按 revisionId 缓存 PDF）。
    @GetMapping("/nodes/{id}/revisions/{revisionId}/preview-pdf")
    public ResponseEntity<Resource> previewRevisionOfficePdf(
            @LoginUser long userId,
            @PathVariable long id,
            @PathVariable long revisionId) {
        return fsNodeService.previewOfficeAsPdfForRevision(userId, id, revisionId);
    }

    // 删除某条历史版本。
    @DeleteMapping("/nodes/{id}/revisions/{revisionId}")
    public void deleteRevision(
            @LoginUser long userId,
            @PathVariable long id,
            @PathVariable long revisionId) {
        fsNodeService.deleteFileRevision(userId, id, revisionId);
    }

    // 删除节点：文件夹要求项目管理员，文件允许管理员或上传者本人。
    @DeleteMapping("/nodes/{id}")
    public void delete(@LoginUser long userId, @PathVariable long id) {
        fsNodeService.deleteNode(userId, id);
    }

    // 重命名文件夹（注意：文件不能改名，只能通过“替换”时带的新文件名改）。
    @PatchMapping("/nodes/{id}/name")
    public FsNodeVO renameFolder(
            @LoginUser long userId,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        return fsNodeService.renameFolder(userId, id, name);
    }

    // 设置项目公开/私有：只允许一级“项目”文件夹，且仅项目管理员。
    @RequestMapping(value = "/nodes/{id}/public", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public FsNodeVO setPublic(
            @LoginUser long userId,
            @PathVariable long id,
            @RequestBody SetPublicRequest body) {
        if (body == null || body.getValue() == null) {
            throw new IllegalArgumentException("请求体需包含 public: true/false");
        }
        return fsNodeService.setPublicRecursive(userId, id, body.getValue());
    }

    // 下载当前文件。
    @GetMapping("/nodes/{id}/download")
    public ResponseEntity<Resource> download(@LoginUser long userId, @PathVariable long id) {
        return fsNodeService.download(userId, id);
    }

    // 当前文件的 Office 在线预览。
    @GetMapping("/nodes/{id}/preview-pdf")
    public ResponseEntity<Resource> previewOfficePdf(@LoginUser long userId, @PathVariable long id) {
        return fsNodeService.previewOfficeAsPdf(userId, id);
    }
}
