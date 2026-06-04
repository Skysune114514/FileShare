package org.example.fileshare1.dto;

// 登录/注册后返回给前端的用户信息。刻意不含 passwordHash：密码永远不出服务端。

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthUserVO {
    private Long id;
    private String name;
    private String email;
    /** user | admin */
    private String role;
}
