package org.example.fileshare1.entity;

// 项目成员实体：表示“用户 X 在项目 Y 里是什么角色”。
// 项目指的是 fs_node 中 parent_id=0 的一级文件夹。

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_member")
public class ProjectMember {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectRootNodeId;
    private Long userId;
    private String role;
    private LocalDateTime createdAt;
}
