package org.example.fileshare1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.fileshare1.config.TestRedisConfiguration;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.LoginRequest;
import org.example.fileshare1.dto.RegisterRequest;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.entity.ProjectMember;
import org.example.fileshare1.entity.User;
import org.example.fileshare1.junit.IntegrationTest;
import org.example.fileshare1.mapper.FsNodeMapper;
import org.example.fileshare1.mapper.ProjectMemberMapper;
import org.example.fileshare1.mapper.UserMapper;
import org.example.fileshare1.service.AuthService;
import org.example.fileshare1.service.FsNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全性集成测试：SQL 注入防护、越权访问、广场非公开资源等（H2 + 临时磁盘）。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Import(TestRedisConfiguration.class)
@DisplayName("[安全] 集成")
class SecurityIntegrationTest {

    private static final Path STORAGE = Path.of("target", "test-file-storage-security");

    @Autowired
    private AuthService authService;
    @Autowired
    private FsNodeService fsNodeService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FsNodeMapper fsNodeMapper;
    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    private long aliceId;

    @DynamicPropertySource
    static void fileStorageRoot(DynamicPropertyRegistry registry) {
        registry.add("file.storage.root", () -> STORAGE.toAbsolutePath().normalize().toString());
    }

    @BeforeEach
    void reset() throws Exception {
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>().ge(ProjectMember::getId, 0));
        fsNodeMapper.delete(new LambdaQueryWrapper<FsNode>().ge(FsNode::getId, 0));
        userMapper.delete(new LambdaQueryWrapper<User>().ge(User::getId, 0));
        if (Files.isDirectory(STORAGE)) {
            try (Stream<Path> walk = Files.walk(STORAGE)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        Files.createDirectories(STORAGE);

        RegisterRequest reg = new RegisterRequest();
        reg.setName("Alice");
        reg.setEmail("alice@sec.test");
        reg.setPassword("alice-pass");
        aliceId = authService.register(reg).getId();
    }

    @IntegrationTest("[安全] 登录 SQL 注入不能绕过认证")
    void sqlInjectionLoginRejected() {
        RegisterRequest odd = new RegisterRequest();
        odd.setName("Odd");
        odd.setEmail("x' OR '1'='1");
        odd.setPassword("odd-pass");
        authService.register(odd);

        LoginRequest attack = new LoginRequest();
        attack.setEmail("' OR '1'='1");
        attack.setPassword("anything");
        assertThrows(IllegalArgumentException.class, () -> authService.login(attack));

        LoginRequest legit = new LoginRequest();
        legit.setEmail("x' OR '1'='1");
        legit.setPassword("odd-pass");
        assertEquals(odd.getEmail(), authService.login(legit).getEmail());
    }

    @IntegrationTest("[安全] 成员候选搜索 SQL 注入不扩大结果集")
    void sqlInjectionMemberSearchDoesNotLeakAllUsers() {
        insertBareUser("Bob", "bob@sec.test");
        insertBareUser("Carol", "carol@sec.test");
        long totalUsers = userMapper.selectCount(new LambdaQueryWrapper<>());

        FsNodeVO project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "安全项目");
        List<?> candidates = fsNodeService.searchProjectMemberCandidates(
                aliceId, project.getId(), "' OR 1=1 --");

        assertTrue(candidates.size() < totalUsers - 1,
                "恶意 q 不应返回库中几乎全部用户");
        assertEquals(totalUsers, userMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    @IntegrationTest("[安全] 非成员不能下载他人私有文件")
    void outsiderCannotDownloadPrivateFile() throws Exception {
        FsNodeVO project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "私有项");
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.txt", "text/plain", "secret".getBytes(StandardCharsets.UTF_8));
        long fileId = fsNodeService.uploadFile(aliceId, project.getId(), file).getId();

        long bobId = insertBareUser("Bob", "bob@sec.test");
        assertThrows(Exception.class, () -> fsNodeService.download(bobId, fileId));
    }

    @IntegrationTest("[安全] 广场不能下载未公开文件")
    void galleryRejectsNonPublicFile() throws Exception {
        FsNodeVO project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "未公开项");
        MockMultipartFile file = new MockMultipartFile(
                "file", "hidden.txt", "text/plain", "hidden".getBytes(StandardCharsets.UTF_8));
        FsNodeVO uploaded = fsNodeService.uploadFile(aliceId, project.getId(), file);
        assertFalse(Boolean.TRUE.equals(uploaded.getIsPublic()));

        assertThrows(IllegalArgumentException.class,
                () -> fsNodeService.downloadPublicFile(aliceId, uploaded.getId()));
    }

    private long insertBareUser(String name, String email) {
        User u = new User();
        u.setName(name);
        u.setEmail(email);
        userMapper.insert(u);
        return u.getId();
    }
}
