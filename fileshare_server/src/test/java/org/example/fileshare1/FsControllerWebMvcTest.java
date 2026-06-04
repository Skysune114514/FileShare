package org.example.fileshare1;

import org.example.fileshare1.config.WebMvcSessionConfig;
import org.example.fileshare1.controller.ApiExceptionHandler;
import org.example.fileshare1.controller.FsController;
import org.example.fileshare1.dto.FsNodeVO;
import org.example.fileshare1.entity.FsNode;
import org.example.fileshare1.exception.FileNameConflictException;
import org.example.fileshare1.security.ApiSessionAuthFilter;
import org.example.fileshare1.security.FsSessionKeys;
import org.example.fileshare1.security.LoginUserArgumentResolver;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.example.fileshare1.junit.UnitTest;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(controllers = FsController.class)
@Import({ApiSessionAuthFilter.class, WebMvcSessionConfig.class, LoginUserArgumentResolver.class, ApiExceptionHandler.class})
class FsControllerWebMvcTest {

    private static final long UID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FsNodeService fsNodeService;

    @MockBean
    private ApiAccessLogMapper apiAccessLogMapper;

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(FsSessionKeys.SESSION_USER_ID, UID);
        s.setAttribute(FsSessionKeys.SESSION_ROLE, UserRoles.USER);
        return s;
    }

    @UnitTest("[单元] GET /api/fs/nodes 未登录返回 401")
    void listNodesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/fs/nodes").param("parentId", "0"))
                .andExpect(status().isUnauthorized());
    }

    @UnitTest("[单元] GET /api/fs/nodes 空间根列表")
    void listNodes() throws Exception {
        when(fsNodeService.listChildren(eq(UID), eq(0L))).thenReturn(List.of(
                FsNodeVO.builder()
                        .id(1L)
                        .parentId(0L)
                        .name("项目A")
                        .nodeType(FsNode.TYPE_FOLDER)
                        .ownerUserId(UID)
                        .uploadedByName("测试")
                        .isPublic(false)
                        .sizeBytes(0L)
                        .createdAt(LocalDateTime.now())
                        .build()
        ));

        mockMvc.perform(get("/api/fs/nodes").param("parentId", "0").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("项目A"));
    }

    @UnitTest("[单元] POST /api/fs/projects 新建项目")
    void createProject() throws Exception {
        when(fsNodeService.createFolder(eq(UID), eq(0L), eq("我的项目")))
                .thenReturn(FsNodeVO.builder()
                        .id(99L)
                        .parentId(0L)
                        .name("我的项目")
                        .nodeType(FsNode.TYPE_FOLDER)
                        .ownerUserId(UID)
                        .uploadedByName("测试")
                        .isPublic(false)
                        .sizeBytes(0L)
                        .createdAt(LocalDateTime.now())
                        .build());

        mockMvc.perform(post("/api/fs/projects")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我的项目\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("我的项目"));
    }

    @UnitTest("[单元] POST /api/fs/files 上传文件")
    void uploadFile() throws Exception {
        when(fsNodeService.uploadFile(eq(UID), eq(2L), any()))
                .thenReturn(FsNodeVO.builder()
                        .id(20L)
                        .parentId(2L)
                        .name("x.txt")
                        .nodeType(FsNode.TYPE_FILE)
                        .ownerUserId(UID)
                        .uploadedByName("测试")
                        .isPublic(false)
                        .sizeBytes(3L)
                        .createdAt(LocalDateTime.now())
                        .build());

        MockMultipartFile file = new MockMultipartFile(
                "file", "x.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/fs/files").file(file).param("parentId", "2")
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("x.txt"));
    }

    @UnitTest("[单元] POST /api/fs/files 同名冲突返回 409")
    void uploadConflict() throws Exception {
        when(fsNodeService.uploadFile(eq(UID), eq(2L), any()))
                .thenThrow(new FileNameConflictException(20L, "dup.txt"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/fs/files").file(file).param("parentId", "2")
                        .session(userSession()))
                .andExpect(status().isConflict());
    }

    @UnitTest("[单元] PATCH /api/fs/nodes/{id}/public 设置公开")
    void setPublic() throws Exception {
        when(fsNodeService.setPublicRecursive(eq(UID), eq(5L), eq(true)))
                .thenReturn(FsNodeVO.builder()
                        .id(5L)
                        .parentId(0L)
                        .name("公开项")
                        .nodeType(FsNode.TYPE_FOLDER)
                        .ownerUserId(UID)
                        .uploadedByName("测试")
                        .isPublic(true)
                        .sizeBytes(0L)
                        .createdAt(LocalDateTime.now())
                        .build());

        mockMvc.perform(patch("/api/fs/nodes/5/public")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"public\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(true));
    }
}
