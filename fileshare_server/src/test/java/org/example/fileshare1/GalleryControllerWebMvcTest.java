package org.example.fileshare1;

import org.example.fileshare1.controller.GalleryController;
import org.example.fileshare1.dto.GalleryProjectVO;
import org.example.fileshare1.dto.PageResult;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.security.ProjectRoles;
import org.example.fileshare1.service.FsNodeService;
import org.example.fileshare1.junit.UnitTest;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(controllers = GalleryController.class)
class GalleryControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FsNodeService fsNodeService;

    @MockBean
    private ApiAccessLogMapper apiAccessLogMapper;

    @UnitTest("[单元] GET /api/fs/gallery/projects 分页（匿名可访问）")
    void listProjects() throws Exception {
        when(fsNodeService.listPublicProjects(eq(1), eq(12))).thenReturn(
                PageResult.<GalleryProjectVO>builder()
                        .content(List.of(GalleryProjectVO.builder()
                                .id(10L)
                                .name("演示项目")
                                .ownerUserId(2L)
                                .ownerName("Bob")
                                .uploadedByName("Bob")
                                .createdAt(LocalDateTime.parse("2026-01-01T12:00:00"))
                                .updatedAt(LocalDateTime.parse("2026-01-02T12:00:00"))
                                .build()))
                        .totalElements(1)
                        .totalPages(1)
                        .page(1)
                        .size(12)
                        .build()
        );

        mockMvc.perform(get("/api/fs/gallery/projects").param("page", "1").param("size", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("演示项目"));
    }

    @UnitTest("[单元] GET /api/fs/gallery/projects/{id}/members 公开项目成员")
    void listProjectMembers() throws Exception {
        when(fsNodeService.listPublicProjectMembers(eq(10L))).thenReturn(List.of(
                ProjectMemberVO.builder()
                        .userId(2L)
                        .name("Bob")
                        .email("bob@test")
                        .role(ProjectRoles.PROJECT_ADMIN)
                        .build()
        ));

        mockMvc.perform(get("/api/fs/gallery/projects/10/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bob"))
                .andExpect(jsonPath("$[0].role").value("project_admin"));
    }
}
