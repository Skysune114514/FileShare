package org.example.fileshare1.dto;

// 项目成员展示对象（不可变，@Value 创建后不能再改）。

import lombok.Builder;
import lombok.Value;
import org.example.fileshare1.entity.ProjectMember;
import org.example.fileshare1.entity.User;

@Value
@Builder
public class ProjectMemberVO {
    long userId;
    String name;
    String email;
    String role;

    public static ProjectMemberVO from(ProjectMember m, User u) {
        String nm = u != null && u.getName() != null && !u.getName().isBlank() ? u.getName().trim() : ("用户" + m.getUserId());
        String em = u != null && u.getEmail() != null ? u.getEmail() : "";
        return ProjectMemberVO.builder()
                .userId(m.getUserId())
                .name(nm)
                .email(em)
                .role(m.getRole())
                .build();
    }
}
