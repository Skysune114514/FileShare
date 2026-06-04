package org.example.fileshare1.dto;

// 注册请求体：昵称/邮箱/密码。真正的格式与查重校验在 AuthServiceImpl。

import lombok.Data;

@Data
public class RegisterRequest {
    private String name;
    private String email;
    private String password;
}
