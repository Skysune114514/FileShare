package org.example.fileshare1.service;

// 认证业务接口。实现类 AuthServiceImpl 只做“校验与写库”，
// 不碰 HttpSession；会话由 AuthController 负责建立。

import org.example.fileshare1.dto.AuthUserVO;
import org.example.fileshare1.dto.LoginRequest;
import org.example.fileshare1.dto.RegisterRequest;

/**
 * 认证业务接口：注册与登录写 user 表；会话由 {@link org.example.fileshare1.controller.AuthController} 建立。
 */
public interface AuthService {

    /** 注册新用户（邮箱唯一、密码 BCrypt），返回不含密码的用户信息。 */
    AuthUserVO register(RegisterRequest req);

    /** 校验邮箱密码，返回用户信息。 */
    AuthUserVO login(LoginRequest req);
}
