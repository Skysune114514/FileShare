package org.example.fileshare1.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.example.fileshare1.dto.AuthUserVO;
import org.example.fileshare1.dto.LoginRequest;
import org.example.fileshare1.dto.RegisterRequest;
import org.example.fileshare1.security.FsSessionKeys;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.AuthService;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口：/api/auth。
 *
 * 职责划分：
 * - AuthService 只负责“验证账号并返回用户信息”；
 * - 本 Controller 额外负责“把登录状态写进服务端 Session”。
 *
 * 为什么 Session 不交给 Service 写：
 * Service 是纯业务，不该依赖 HttpServletRequest/HttpSession；
 * Controller 才是 Web 层，知道如何把业务结果翻译成 Web 状态。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    // 认证业务接口，真正的“查库/校验密码”都在实现类里。
    private final AuthService authService;

    // 注册接口：先建账号，成功后自动登录（建立 Session）。
    @PostMapping("/register")
    public AuthUserVO register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        // 注册成功拿到不含密码的用户信息。
        AuthUserVO vo = authService.register(body);
        // 写入 Session，这样前端注册完无需再登录一次。
        establishSession(request, vo);
        return vo;
    }

    // 登录接口：校验邮箱密码，成功后写 Session。
    @PostMapping("/login")
    public AuthUserVO login(@RequestBody LoginRequest body, HttpServletRequest request) {
        AuthUserVO vo = authService.login(body);
        establishSession(request, vo);
        return vo;
    }

    // 登出接口：直接销毁 Session。
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        // getSession(false)：拿不到就返回 null，不会新建一个空 Session。
        HttpSession s = request.getSession(false);
        if (s != null) {
            // 销毁后 Redis 里的会话也没了，浏览器再带旧 Cookie 也查不到用户。
            s.invalidate();
        }
    }

    // 把登录身份写入 Session 的公共逻辑，register 和 login 共用。
    private static void establishSession(HttpServletRequest request, AuthUserVO vo) {
        // 创建（或复用已有）HttpSession。因为配了 Spring Session，实际底层存在 Redis。
        HttpSession session = request.getSession(true);
        // 写用户 id。
        session.setAttribute(FsSessionKeys.SESSION_USER_ID, vo.getId());
        // 写平台角色；为兼容老数据，空角色默认普通用户。
        String role = vo.getRole() == null || vo.getRole().isBlank() ? UserRoles.USER : vo.getRole();
        session.setAttribute(FsSessionKeys.SESSION_ROLE, role);
    }
}
