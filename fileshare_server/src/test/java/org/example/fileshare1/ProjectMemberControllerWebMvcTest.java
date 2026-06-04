package org.example.fileshare1;

import org.example.fileshare1.config.WebMvcSessionConfig;
import org.example.fileshare1.controller.ProjectMemberController;
import org.example.fileshare1.dto.ProjectMemberVO;
import org.example.fileshare1.junit.UnitTest;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.example.fileshare1.security.ApiSessionAuthFilter;
import org.example.fileshare1.security.FsSessionKeys;
import org.example.fileshare1.security.LoginUserArgumentResolver;
import org.example.fileshare1.security.ProjectRoles;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(controllers = ProjectMemberController.class)
@Import({ApiSessionAuthFilter.class, WebMvcSessionConfig.class, LoginUserArgumentResolver.class})
class ProjectMemberControllerWebMvcTest {

    private static final long UID = 5L;
    private static final long ROOT_ID = 100L;

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

    @UnitTest("[单元] GET /api/fs/projects/{rootId}/members 返回成员列表")
    void listMembers() throws Exception {
        when(fsNodeService.listProjectMembers(eq(UID), eq(ROOT_ID))).thenReturn(List.of(
                ProjectMemberVO.builder()
                        .userId(UID)
                        .name("Alice")
                        .email("a@test")
                        .role(ProjectRoles.PROJECT_ADMIN)
                        .build()
        ));

        mockMvc.perform(get("/api/fs/projects/" + ROOT_ID + "/members").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[0].role").value("project_admin"));
    }

    @UnitTest("[单元] GET /api/fs/projects/{rootId}/my-role")
    void myRole() throws Exception {
        when(fsNodeService.getMyProjectRole(eq(UID), eq(ROOT_ID))).thenReturn(ProjectRoles.MEMBER);

        mockMvc.perform(get("/api/fs/projects/" + ROOT_ID + "/my-role").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("member"));
    }

    @UnitTest("[单元] POST /api/fs/projects/{rootId}/members 添加成员")
    void addMember() throws Exception {
        mockMvc.perform(post("/api/fs/projects/" + ROOT_ID + "/members")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":9,\"role\":\"member\"}"))
                .andExpect(status().isOk());

        verify(fsNodeService).addProjectMember(eq(UID), eq(ROOT_ID), eq(9L), eq("member"));
    }

    @UnitTest("[单元] DELETE /api/fs/projects/{rootId}/members/{userId}")
    void removeMember() throws Exception {
        mockMvc.perform(delete("/api/fs/projects/" + ROOT_ID + "/members/9").session(userSession()))
                .andExpect(status().isOk());

        verify(fsNodeService).removeProjectMember(eq(UID), eq(ROOT_ID), eq(9L));
    }

    @UnitTest("[单元] 未登录访问成员接口返回 401")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/fs/projects/" + ROOT_ID + "/members"))
                .andExpect(status().isUnauthorized());
    }
}
