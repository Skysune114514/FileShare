package org.example.fileshare1.service.impl;

// 认证业务实现：注册=校验+查重+BCrypt 入库；登录=查用户+BCrypt 比对。
// 注意这里完全不碰 Session——它只保证“这个人是谁、密码对不对”。

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.fileshare1.dto.AuthUserVO;
import org.example.fileshare1.dto.LoginRequest;
import org.example.fileshare1.dto.RegisterRequest;
import org.example.fileshare1.entity.User;
import org.example.fileshare1.mapper.UserMapper;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 注册与登录：写 {@link org.example.fileshare1.entity.User} 表，密码 BCrypt；
 * 由 {@link org.example.fileshare1.controller.AuthController} 建立 Session。
 */
@Service
public class AuthServiceImpl implements AuthService {

    // user 表 Mapper：查询与插入用户。
    private final UserMapper userMapper;
    // BCrypt 编码器：注册加密、登录比对（matches）。
    private final PasswordEncoder passwordEncoder;

    // Spring 通过构造器自动注入上面两个 Bean。
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册：校验昵称/邮箱/密码 → 查重邮箱 → BCrypt 写库 → 返回 VO。
     */
    @Override
    public AuthUserVO register(RegisterRequest req) {
        // 步骤1：校验昵称、邮箱、密码长度
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("昵称为空");
        }
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            throw new IllegalArgumentException("邮箱为空");
        }
        if (req.getPassword() == null || req.getPassword().length() < 4) {
            throw new IllegalArgumentException("密码至少 4 位");
        }
        // 查重邮箱：selectCount 大于 0 表示已被注册。trim 保证 “a@x.com ” 与 “a@x.com” 视为相同。
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.eq(User::getEmail, req.getEmail().trim());
        if (userMapper.selectCount(q) > 0) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        // 把明文密码交给 BCrypt 生成带盐哈希，再写库。
        User u = new User();
        u.setName(req.getName().trim());
        u.setEmail(req.getEmail().trim());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setRole(UserRoles.USER);
        userMapper.insert(u);
        // 返回不含密码的用户信息；登录会话由 Controller 建立。
        return toVo(u);
    }

    /**
     * 登录：按邮箱查用户 → BCrypt 校验密码 → 返回 VO。
     */
    @Override
    public AuthUserVO login(LoginRequest req) {
        // 空值直接提示，避免拿 null 去 SQL 查询。
        if (req.getEmail() == null || req.getPassword() == null) {
            throw new IllegalArgumentException("邮箱或密码为空");
        }
        // 按邮箱找用户；找不到或老数据没有密码都给出同一句提示。
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.eq(User::getEmail, req.getEmail().trim());
        User u = userMapper.selectOne(q);
        if (u == null || u.getPasswordHash() == null) {
            throw new IllegalArgumentException("用户不存在或未设置密码");
        }
        // BCrypt 密文不能直接 equals，必须用 matches(明文, 密文)。
        if (!passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        return toVo(u);
    }

    // 实体转 VO 的兜底细节：角色为空时补成普通用户。
    private static AuthUserVO toVo(User u) {
        String role = u.getRole() == null || u.getRole().isBlank() ? UserRoles.USER : u.getRole().trim();
        return AuthUserVO.builder()
                .id(u.getId())
                .name(u.getName())
                .email(u.getEmail())
                .role(role)
                .build();
    }
}
