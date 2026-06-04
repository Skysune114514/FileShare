package org.example.fileshare1;

import org.example.fileshare1.config.WebMvcSessionConfig;
import org.example.fileshare1.controller.AdminController;
import org.example.fileshare1.junit.UnitTest;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.example.fileshare1.security.ApiSessionAuthFilter;
import org.example.fileshare1.security.FsSessionKeys;
import org.example.fileshare1.security.LoginUserArgumentResolver;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.FsNodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(controllers = AdminController.class)
@Import({ApiSessionAuthFilter.class, WebMvcSessionConfig.class, LoginUserArgumentResolver.class})
class AdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FsNodeService fsNodeService;

    @MockBean
    private ApiAccessLogMapper apiAccessLogMapper;

    @UnitTest("[安全] 普通用户访问管理接口返回 403")
    void nonAdminForbidden() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FsSessionKeys.SESSION_USER_ID, 2L);
        session.setAttribute(FsSessionKeys.SESSION_ROLE, UserRoles.USER);

        mockMvc.perform(get("/api/admin/access-logs").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("需要管理员权限"));
    }
}
