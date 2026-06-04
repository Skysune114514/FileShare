package org.example.fileshare1.dto;

// 登录请求体：前端 POST /api/auth/login 的 JSON 会绑定到这个对象。

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
}
