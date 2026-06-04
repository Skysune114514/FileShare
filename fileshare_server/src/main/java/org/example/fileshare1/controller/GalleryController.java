package org.example.fileshare1.controller;

import lombok.RequiredArgsConstructor;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.GalleryProjectVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 广场接口（/api/fs/gallery）：只读浏览他人公开内容。
 *
 * 和登录态 /api/fs 的差别：
 * /api/fs 操作自己的树，需要登录；
 * /api/fs/gallery 是“公开市场”，匿名可访问（Filter 白名单），
 * 但每个方法内部仍会用 is_public / ownerUserId 再查一次，防止访问到未公开数据。
 */
@RestController
@RequestMapping("/api/fs/gallery")
@RequiredArgsConstructor
public class GalleryController {

    private final FsNodeService fsNodeService;

    // 广场首页分页数据源：公开的一级项目，按更新时间倒序。
    @GetMapping("/projects")
    public PageResult<GalleryProjectVO> projects(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return fsNodeService.listPublicProjects(page, size);
    }

    // 公开项目成员展示：项目必须 is_public，成员信息是只读的。
    @GetMapping("/projects/{projectRootId}/members")
    public List<ProjectMemberVO> listProjectMembers(@PathVariable long projectRootId) {
        return fsNodeService.listPublicProjectMembers(projectRootId);
    }

    // 浏览某用户公开目录；parentId=0 时是该用户全部公开项目根。
    @GetMapping("/{ownerId}/nodes")
    public List<FsNodeVO> listNodes(
            @PathVariable long ownerId,
            @RequestParam(defaultValue = "0") long parentId) {
        return fsNodeService.listPublicChildren(ownerId, parentId);
    }

    // 匿名下载公开文件。
    @GetMapping("/{ownerId}/files/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable long ownerId, @PathVariable long fileId) {
        return fsNodeService.downloadPublicFile(ownerId, fileId);
    }

    // 匿名预览公开 Office 文档。
    @GetMapping("/{ownerId}/files/{fileId}/preview-pdf")
    public ResponseEntity<Resource> previewOfficePdf(@PathVariable long ownerId, @PathVariable long fileId) {
        return fsNodeService.previewPublicOfficeAsPdf(ownerId, fileId);
    }
}
