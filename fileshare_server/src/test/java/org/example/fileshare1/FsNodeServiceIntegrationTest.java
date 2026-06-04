package org.example.fileshare1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.fileshare1.dto.FsNodeRevisionVO;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.dto.GalleryProjectVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.entity.FsNodeRevision;
import org.example.fileshare1.entity.ProjectMember;
import org.example.fileshare1.entity.User;
import org.example.fileshare1.exception.FileNameConflictException;
import org.example.fileshare1.mapper.FsNodeMapper;
import org.example.fileshare1.mapper.FsNodeRevisionMapper;
import org.example.fileshare1.mapper.ProjectMemberMapper;
import org.example.fileshare1.mapper.UserMapper;
import org.example.fileshare1.security.ProjectRoles;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.example.fileshare1.config.TestRedisConfiguration;
import org.example.fileshare1.junit.IntegrationTest;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三章核心流程集成测试：个人文件/项目、广场公开、成员协作（H2 + 临时磁盘）。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Import(TestRedisConfiguration.class)
class FsNodeServiceIntegrationTest {

    private static final Path STORAGE = Path.of("target", "test-file-storage-fs-service");

    @Autowired
    private FsNodeService fsNodeService;
    @Autowired
    private FsNodeMapper fsNodeMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FsNodeRevisionMapper fsNodeRevisionMapper;
    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    private long aliceId;

    @DynamicPropertySource
    static void fileStorageRoot(DynamicPropertyRegistry registry) {
        registry.add("file.storage.root", () -> STORAGE.toAbsolutePath().normalize().toString());
    }

    @BeforeEach
    void resetDatabaseAndFiles() throws IOException {
        fsNodeRevisionMapper.delete(new LambdaQueryWrapper<FsNodeRevision>().ge(FsNodeRevision::getId, 0));
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>().ge(ProjectMember::getId, 0));
        fsNodeMapper.delete(new LambdaQueryWrapper<FsNode>().ge(FsNode::getId, 0));
        userMapper.delete(new LambdaQueryWrapper<User>().ge(User::getId, 0));
        if (Files.isDirectory(STORAGE)) {
            try (Stream<Path> walk = Files.walk(STORAGE)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(STORAGE);

        User alice = new User();
        alice.setName("Alice");
        alice.setEmail("alice@test");
        alice.setRole(UserRoles.USER);
        userMapper.insert(alice);
        aliceId = alice.getId();
    }

    private long insertUser(String name, String email) {
        User u = new User();
        u.setName(name);
        u.setEmail(email);
        u.setRole(UserRoles.USER);
        userMapper.insert(u);
        return u.getId();
    }

    private static byte[] readAll(Resource body) throws IOException {
        try (var in = Objects.requireNonNull(body).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.1 空间根列表")
    class RootList {

        @IntegrationTest("[集成] 创建者可见自有项目")
        void ownerSeesOwnProject() {
            fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "我的项目");
            List<FsNodeVO> roots = fsNodeService.listChildren(aliceId, FsNode.ROOT_PARENT_ID);
            assertEquals(1, roots.size());
            assertEquals("我的项目", roots.get(0).getName());
        }

        @IntegrationTest("[集成] 成员在空间根可见被邀请的项目")
        void memberSeesJoinedProject() {
            long bobId = insertUser("Bob", "bob-root@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "协作根");
            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);

            List<FsNodeVO> bobRoots = fsNodeService.listChildren(bobId, FsNode.ROOT_PARENT_ID);
            assertEquals(1, bobRoots.size());
            assertEquals(project.getId(), bobRoots.get(0).getId());
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.2 项目内子目录列表")
    class InProjectList {

        @IntegrationTest("[集成] 协作者与创建者看到同一命名空间下的子节点")
        void collaboratorSeesSameNamespaceTree() {
            long bobId = insertUser("Bob", "bob-tree@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "协作树");
            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);

            fsNodeService.createFolder(bobId, project.getId(), "Bob子夹");

            List<FsNodeVO> aliceView = fsNodeService.listChildren(aliceId, project.getId());
            assertEquals(1, aliceView.size());
            assertEquals("Bob子夹", aliceView.get(0).getName());
            assertEquals(aliceId, aliceView.get(0).getOwnerUserId());
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.3 上传与同名冲突")
    class Upload {

        @IntegrationTest("[集成] 禁止在空间根直接上传文件")
        void rejectUploadAtRoot() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "root.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.uploadFile(aliceId, FsNode.ROOT_PARENT_ID, file));
        }

        @IntegrationTest("[集成] 同名文件再次上传抛出冲突异常")
        void duplicateFileConflict() {
            var dir = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "上传目录");
            MockMultipartFile first = new MockMultipartFile(
                    "file", "dup.txt", "text/plain", "v1".getBytes(StandardCharsets.UTF_8));
            fsNodeService.uploadFile(aliceId, dir.getId(), first);
            MockMultipartFile second = new MockMultipartFile(
                    "file", "dup.txt", "text/plain", "v2".getBytes(StandardCharsets.UTF_8));
            FileNameConflictException ex = assertThrows(FileNameConflictException.class,
                    () -> fsNodeService.uploadFile(aliceId, dir.getId(), second));
            assertTrue(ex.getExistingFileId() > 0);
            assertEquals("dup.txt", ex.getFileName());
        }

        @IntegrationTest("[集成] 同名文件夹存在时拒绝上传文件")
        void rejectUploadWhenFolderWithSameName() {
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "上传项");
            fsNodeService.createFolder(aliceId, project.getId(), "同名");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "同名", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.uploadFile(aliceId, project.getId(), file));
        }

        @IntegrationTest("[集成] 上传成功并可下载")
        void uploadAndDownload() throws Exception {
            var dir = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "资料");
            MockMultipartFile multipart = new MockMultipartFile(
                    "file", "readme.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
            var fileVo = fsNodeService.uploadFile(aliceId, dir.getId(), multipart);
            assertEquals("readme.txt", fileVo.getName());
            assertEquals("Alice", fileVo.getUploadedByName());

            ResponseEntity<Resource> resp = fsNodeService.download(aliceId, fileVo.getId());
            assertEquals(200, resp.getStatusCode().value());
            assertEquals("hello", new String(readAll(resp.getBody()), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.4 替换与版本")
    class ReplaceAndRevision {

        @IntegrationTest("[集成] 替换后保留历史版本并可下载旧版")
        void replaceCreatesRevision() throws Exception {
            var dir = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "版本目录");
            MockMultipartFile v1 = new MockMultipartFile(
                    "file", "doc.txt", "text/plain", "v1".getBytes(StandardCharsets.UTF_8));
            var fileVo = fsNodeService.uploadFile(aliceId, dir.getId(), v1);
            MockMultipartFile v2 = new MockMultipartFile(
                    "file", "doc.txt", "text/plain", "v2".getBytes(StandardCharsets.UTF_8));
            fsNodeService.replaceFileContent(aliceId, fileVo.getId(), v2);

            List<FsNodeRevisionVO> revs = fsNodeService.listFileRevisions(aliceId, fileVo.getId());
            assertEquals(1, revs.size());

            ResponseEntity<Resource> cur = fsNodeService.download(aliceId, fileVo.getId());
            assertEquals("v2", new String(readAll(cur.getBody()), StandardCharsets.UTF_8));

            ResponseEntity<Resource> old = fsNodeService.downloadFileRevision(
                    aliceId, fileVo.getId(), revs.get(0).getId());
            assertEquals("v1", new String(readAll(old.getBody()), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.5 删除子树")
    class Delete {

        @IntegrationTest("[集成] 项目管理员可递归删除文件夹子树")
        void adminDeletesFolderSubtree() {
            var dir = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "待删");
            MockMultipartFile multipart = new MockMultipartFile(
                    "file", "a.bin", "application/octet-stream", new byte[]{1, 2, 3});
            var fileVo = fsNodeService.uploadFile(aliceId, dir.getId(), multipart);
            Path physical = STORAGE.resolve(fsNodeMapper.selectById(fileVo.getId()).getStorageKey());
            assertTrue(Files.isRegularFile(physical));

            fsNodeService.deleteNode(aliceId, dir.getId());
            assertNull(fsNodeMapper.selectById(dir.getId()));
            assertNull(fsNodeMapper.selectById(fileVo.getId()));
            assertFalse(Files.isRegularFile(physical));
        }

        @IntegrationTest("[集成] 普通成员不可删除文件夹")
        void memberCannotDeleteFolder() {
            long bobId = insertUser("Bob", "bob-del-folder@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "删夹项");
            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);
            var sub = fsNodeService.createFolder(aliceId, project.getId(), "子夹");
            assertThrows(IllegalArgumentException.class, () -> fsNodeService.deleteNode(bobId, sub.getId()));
        }

        @IntegrationTest("[集成] 成员可删本人文件，不可删他人文件")
        void memberDeletesOwnFileOnly() {
            long bobId = insertUser("Bob", "bob-del-file@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "删文件项");
            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);

            MockMultipartFile bobFile = new MockMultipartFile(
                    "file", "b.txt", "text/plain", "bob".getBytes(StandardCharsets.UTF_8));
            var bobUploaded = fsNodeService.uploadFile(bobId, project.getId(), bobFile);
            MockMultipartFile aliceFile = new MockMultipartFile(
                    "file", "a.txt", "text/plain", "alice".getBytes(StandardCharsets.UTF_8));
            var aliceUploaded = fsNodeService.uploadFile(aliceId, project.getId(), aliceFile);

            assertDoesNotThrow(() -> fsNodeService.deleteNode(bobId, bobUploaded.getId()));
            assertNull(fsNodeMapper.selectById(bobUploaded.getId()));
            assertThrows(IllegalArgumentException.class, () -> fsNodeService.deleteNode(bobId, aliceUploaded.getId()));
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.6 项目公开")
    class PublicGallery {

        @IntegrationTest("[集成] 仅项目根可设公开，子目录不可单独设")
        void publicOnlyOnProjectRoot() {
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "公开根");
            var inner = fsNodeService.createFolder(aliceId, project.getId(), "内夹");
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.setPublicRecursive(aliceId, inner.getId(), true));
            assertDoesNotThrow(() -> fsNodeService.setPublicRecursive(aliceId, project.getId(), true));
        }

        @IntegrationTest("[集成] 公开后新建子节点继承 isPublic")
        void childrenInheritPublic() {
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "公开继承");
            fsNodeService.setPublicRecursive(aliceId, project.getId(), true);
            var sub = fsNodeService.createFolder(aliceId, project.getId(), "子");
            assertTrue(Boolean.TRUE.equals(fsNodeMapper.selectById(sub.getId()).getIsPublic()));
        }

        @IntegrationTest("[集成] 广场分页列出公开项目")
        void listPublicProjectsPaged() {
            long bobId = insertUser("Bob", "bob-pub@test");
            var p1 = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "公开甲");
            fsNodeService.setPublicRecursive(aliceId, p1.getId(), true);
            var p2 = fsNodeService.createFolder(bobId, FsNode.ROOT_PARENT_ID, "公开乙");
            fsNodeService.setPublicRecursive(bobId, p2.getId(), true);

            PageResult<GalleryProjectVO> page1 = fsNodeService.listPublicProjects(1, 1);
            assertEquals(2, page1.getTotalElements());
            assertEquals(1, page1.getContent().size());
        }
    }

    @Nested
    @DisplayName("[集成] 3.7.8 成员管理")
    class Members {

        @IntegrationTest("[集成] 项目成员可查看成员列表（含创建者）")
        void memberCanListMembers() {
            long bobId = insertUser("Bob", "bob-members@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "成员项");
            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);

            List<ProjectMemberVO> list = fsNodeService.listProjectMembers(bobId, project.getId());
            assertTrue(list.stream().anyMatch(m -> m.getUserId() == aliceId && ProjectRoles.PROJECT_ADMIN.equals(m.getRole())));
            assertTrue(list.stream().anyMatch(m -> m.getUserId() == bobId && ProjectRoles.MEMBER.equals(m.getRole())));
        }

        @IntegrationTest("[集成] 非成员无权查看成员列表")
        void outsiderDenied() {
            long carolId = insertUser("Carol", "carol@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "私密成员");
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.listProjectMembers(carolId, project.getId()));
        }

        @IntegrationTest("[集成] 广场访客可查看公开项目成员；未公开项目拒绝")
        void publicMembersList() {
            var pub = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "广场成员");
            fsNodeService.setPublicRecursive(aliceId, pub.getId(), true);
            assertFalse(fsNodeService.listPublicProjectMembers(pub.getId()).isEmpty());

            var priv = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "未公开");
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.listPublicProjectMembers(priv.getId()));
        }

        @IntegrationTest("[集成] 添加/移除成员；历史 uploaded_by_name 不变")
        void addRemoveMemberPreservesUploaderName() {
            long bobId = insertUser("Bob", "bob-audit@test");
            var project = fsNodeService.createFolder(aliceId, FsNode.ROOT_PARENT_ID, "审计项");
            MockMultipartFile f = new MockMultipartFile(
                    "file", "keep.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
            var fileVo = fsNodeService.uploadFile(aliceId, project.getId(), f);
            String before = fsNodeMapper.selectById(fileVo.getId()).getUploadedByName();

            fsNodeService.addProjectMember(aliceId, project.getId(), bobId, ProjectRoles.MEMBER);
            assertThrows(IllegalArgumentException.class,
                    () -> fsNodeService.addProjectMember(aliceId, project.getId(), aliceId, ProjectRoles.MEMBER));

            fsNodeService.removeProjectMember(aliceId, project.getId(), bobId);
            assertEquals(before, fsNodeMapper.selectById(fileVo.getId()).getUploadedByName());
        }
    }
}
