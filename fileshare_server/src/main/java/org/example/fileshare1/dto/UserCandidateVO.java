package org.example.fileshare1.dto;

// 搜索可邀请成员时的候选用户对象。

import lombok.Builder;
import lombok.Value;
import org.example.fileshare1.entity.User;

@Value
@Builder
public class UserCandidateVO {
    long id;
    String name;
    String email;

    public static UserCandidateVO from(User u) {
        String nm = u.getName() != null && !u.getName().isBlank() ? u.getName().trim() : ("用户" + u.getId());
        return UserCandidateVO.builder()
                .id(u.getId())
                .name(nm)
                .email(u.getEmail() != null ? u.getEmail() : "")
                .build();
    }
}
