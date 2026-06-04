package org.example.fileshare1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.fileshare1.config.TestRedisConfiguration;
import org.example.fileshare1.dto.CreateShareRequest;
import org.example.fileshare1.dto.ShareCreateResponse;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.entity.User;
import org.example.fileshare1.junit.IntegrationTest;
import org.example.fileshare1.mapper.FsNodeMapper;
import org.example.fileshare1.mapper.UserMapper;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.example.fileshare1.service.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分享子系统 + Mock Redis 集成测试（与文件树 H2 测试同 profile，Redis 不落真实服务）。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Import(TestRedisConfiguration.class)
@DisplayName("[集成] 分享 + Mock Redis")
class ShareLinkRedisIntegrationTest {

  private static final Path STORAGE = Path.of("target", "test-file-storage-share-redis");

  @Autowired
  private ShareLinkService shareLinkService;
  @Autowired
  private FsNodeService fsNodeService;
  @Autowired
  private FsNodeMapper fsNodeMapper;
  @Autowired
  private UserMapper userMapper;
  @Autowired
  private TestRedisConfiguration testRedisConfiguration;

  private long userId;
  private long fileId;

  @DynamicPropertySource
  static void fileStorageRoot(DynamicPropertyRegistry registry) {
    registry.add("file.storage.root", () -> STORAGE.toAbsolutePath().normalize().toString());
  }

  @BeforeEach
  void reset() throws Exception {
    testRedisConfiguration.clear();
    fsNodeMapper.delete(new LambdaQueryWrapper<FsNode>().ge(FsNode::getId, 0));
    userMapper.delete(new LambdaQueryWrapper<User>().ge(User::getId, 0));
    if (Files.isDirectory(STORAGE)) {
      try (var walk = Files.walk(STORAGE)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (Exception ignored) {
          }
        });
      }
    }
    Files.createDirectories(STORAGE);

    User u = new User();
    u.setName("Sharer");
    u.setEmail("sharer@test");
    u.setRole(UserRoles.USER);
    userMapper.insert(u);
    userId = u.getId();

    var project = fsNodeService.createFolder(userId, FsNode.ROOT_PARENT_ID, "分享测试项");
    MockMultipartFile multipart = new MockMultipartFile(
        "file", "share-demo.txt", "text/plain", "share-content".getBytes(StandardCharsets.UTF_8));
    fileId = fsNodeService.uploadFile(userId, project.getId(), multipart).getId();
  }

  @IntegrationTest("[集成][Redis] 创建分享并写入 Mock Redis")
  void createAndMeta() {
    ShareCreateResponse created = shareLinkService.create(userId, request(fileId, "24h", null));
    assertNotNull(created.getCode());
    assertTrue(created.getTtlSeconds() > 0);

    var meta = shareLinkService.meta(created.getCode());
    assertEquals("share-demo.txt", meta.getFileName());
    assertFalse(meta.isRequiresPin());
  }

  @IntegrationTest("[集成][Redis] 带 PIN 解锁后可下载")
  void unlockWithPinAndDownload() throws Exception {
    ShareCreateResponse created = shareLinkService.create(userId, request(fileId, "3h", "1234"));
    assertTrue(shareLinkService.meta(created.getCode()).isRequiresPin());

    String grant = shareLinkService.unlock(created.getCode(), "1234");
    ResponseEntity<Resource> resp = shareLinkService.download(created.getCode(), grant);
    assertEquals(200, resp.getStatusCode().value());
    assertNotNull(resp.getBody());
    try (var in = resp.getBody().getInputStream()) {
      assertEquals("share-content", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @IntegrationTest("[集成][Redis] 错误 PIN 拒绝下载")
  void wrongPinRejected() {
    ShareCreateResponse created = shareLinkService.create(userId, request(fileId, "3h", "1234"));
    assertThrows(IllegalArgumentException.class,
        () -> shareLinkService.unlock(created.getCode(), "0000"));
  }

  @IntegrationTest("[安全] 有 PIN 时未 unlock 不能下载")
  void pinRequiredWithoutGrantRejected() {
    ShareCreateResponse created = shareLinkService.create(userId, request(fileId, "3h", "1234"));
    assertTrue(shareLinkService.meta(created.getCode()).isRequiresPin());
    assertThrows(IllegalArgumentException.class,
        () -> shareLinkService.download(created.getCode(), null));
  }

  @IntegrationTest("[集成][Redis] 分享码不存在")
  void missingCode() {
    assertThrows(IllegalArgumentException.class, () -> shareLinkService.meta("NOTEXIST12"));
  }

  private static CreateShareRequest request(long nodeId, String ttl, String pin) {
    CreateShareRequest req = new CreateShareRequest();
    req.setNodeId(nodeId);
    req.setTtl(ttl);
    req.setPin(pin);
    return req;
  }
}
