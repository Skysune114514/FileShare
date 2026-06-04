package org.example.fileshare1.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.aop.ApiOperationLabels;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.entity.ApiAccessLog;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.example.fileshare1.security.LoginUser;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员接口（/api/admin），由 Filter 保证只有 role=admin 能进。
 *
 * 两个能力：
 * 1. 撤销广场公开：管理员可以对任意公开节点（项目根/子夹/文件）整棵子树取消公开；
 * 2. 查询访问日志：分页查看 api_access_log，并把内部英文操作名转成中文。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    // 撤销公开走文件树服务；查日志直接操作 Mapper（这里不需要 Service 包装）。
    private final FsNodeService fsNodeService;
    private final ApiAccessLogMapper apiAccessLogMapper;

    // 平台管理员撤销公开。
    @PostMapping("/gallery/projects/{nodeId}/unpublish")
    public FsNodeVO unpublishPublicNode(@LoginUser long adminId, @PathVariable long nodeId) {
        return fsNodeService.adminUnpublishPublicProject(adminId, nodeId);
    }

    // 访问日志分页：先规范参数，再 count + 分页查，最后把操作名显示成中文。
    @GetMapping("/access-logs")
    public PageResult<ApiAccessLog> accessLogs(
            @LoginUser long adminId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 页码至少 1；每页 1~100，防手滑传负数/超大值。
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        // 先统计总数，用于前端算总页数。
        long total = apiAccessLogMapper.selectCount(new LambdaQueryWrapper<>());
        int offset = (p - 1) * s;
        // 按 id 倒序（最新在前），用 MySQL 的 LIMIT offset, size 分页。
        LambdaQueryWrapper<ApiAccessLog> w = new LambdaQueryWrapper<>();
        w.orderByDesc(ApiAccessLog::getId).last("LIMIT " + offset + ", " + s);
        List<ApiAccessLog> list = apiAccessLogMapper.selectList(w);
        for (ApiAccessLog row : list) {
            // 数据库里可能存中文/旧英文/脏数据，统一转换成适合展示的中文。
            row.setControllerMethod(ApiOperationLabels.toDisplayLabel(
                    row.getControllerMethod(), row.getHttpMethod(), row.getRequestUri()));
        }
        // 计算总页数：向上取整。
        int totalPages = s > 0 ? (int) ((total + s - 1) / s) : 0;
        // 包装成统一分页结构返回。
        return PageResult.<ApiAccessLog>builder()
                .content(list)
                .totalElements(total)
                .totalPages(totalPages)
                .page(p)
                .size(s)
                .build();
    }
}
