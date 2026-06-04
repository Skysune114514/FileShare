package org.example.fileshare1.controller;

import lombok.RequiredArgsConstructor;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.dto.UserCandidateVO;
import org.example.fileshare1.security.LoginUser;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目成员接口（/api/fs/projects/{rootId}）。
 *
 * 注意路径前缀和 FsController 是兄弟关系：/api/fs/projects 属于文件树域下的“成员子域”。
 * 查看成员只要项目内读权限；添加/移除/搜索候选人只有项目管理员能做。
 */
@RestController
@RequestMapping("/api/fs/projects")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final FsNodeService fsNodeService;

    // 返回 {role: "project_admin"|"member"|""}；非成员给空串，前端据此隐藏“成员管理”按钮。
    @GetMapping("/{rootId}/my-role")
    public Map<String, String> myRole(@LoginUser long userId, @PathVariable long rootId) {
        String r = fsNodeService.getMyProjectRole(userId, rootId);
        return Map.of("role", r != null ? r : "");
    }

    // 成员列表：返回创建者 + project_member 表里的成员。
    @GetMapping("/{rootId}/members")
    public List<ProjectMemberVO> listMembers(@LoginUser long userId, @PathVariable long rootId) {
        return fsNodeService.listProjectMembers(userId, rootId);
    }

    // 搜索可邀请用户：按昵称/邮箱模糊搜索，服务端排除已有成员和创建者。
    @GetMapping("/{rootId}/member-candidates")
    public List<UserCandidateVO> candidates(
            @LoginUser long userId,
            @PathVariable long rootId,
            @RequestParam(required = false) String q) {
        return fsNodeService.searchProjectMemberCandidates(userId, rootId, q);
    }

    // 添加成员：body 必须带 userId，role 可选（缺省 member）。
    @PostMapping("/{rootId}/members")
    public void addMember(
            @LoginUser long userId,
            @PathVariable long rootId,
            @RequestBody Map<String, Object> body) {
        if (body == null || body.get("userId") == null) {
            throw new IllegalArgumentException("请求体需包含 userId");
        }
        long targetUserId = ((Number) body.get("userId")).longValue();
        String role = body.get("role") instanceof String ? (String) body.get("role") : null;
        fsNodeService.addProjectMember(userId, rootId, targetUserId, role);
    }

    // 移除成员：创建者不可移除由 Service 校验兜底。
    @DeleteMapping("/{rootId}/members/{targetUserId}")
    public void removeMember(
            @LoginUser long userId,
            @PathVariable long rootId,
            @PathVariable long targetUserId) {
        fsNodeService.removeProjectMember(userId, rootId, targetUserId);
    }
}
