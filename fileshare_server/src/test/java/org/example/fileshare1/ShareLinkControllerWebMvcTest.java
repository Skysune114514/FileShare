package org.example.fileshare1;

import jakarta.servlet.http.Cookie;
import org.example.fileshare1.config.WebMvcSessionConfig;
import org.example.fileshare1.controller.ShareLinkController;
import org.example.fileshare1.dto.CreateShareRequest;
import org.example.fileshare1.dto.ShareCreateResponse;
import org.example.fileshare1.dto.ShareMetaResponse;
import org.example.fileshare1.security.ApiSessionAuthFilter;
import org.example.fileshare1.security.FsSessionKeys;
import org.example.fileshare1.security.LoginUserArgumentResolver;
import org.example.fileshare1.security.UserRoles;
import org.example.fileshare1.service.ShareLinkService;
import org.example.fileshare1.share.ShareConstants;
import org.example.fileshare1.junit.UnitTest;
import org.example.fileshare1.mapper.ApiAccessLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(controllers = ShareLinkController.class)
@Import({ApiSessionAuthFilter.class, WebMvcSessionConfig.class, LoginUserArgumentResolver.class})
class ShareLinkControllerWebMvcTest {

    private static final long UID = 42L;
    private static final String CODE = "ABCDEFGHJK";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShareLinkService shareLinkService;

    @MockBean
    private ApiAccessLogMapper apiAccessLogMapper;

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(FsSessionKeys.SESSION_USER_ID, UID);
        s.setAttribute(FsSessionKeys.SESSION_ROLE, UserRoles.USER);
        return s;
    }

    @UnitTest("[单元] POST /api/share 创建分享")
    void createShare() throws Exception {
        when(shareLinkService.create(eq(UID), any(CreateShareRequest.class)))
                .thenReturn(ShareCreateResponse.builder()
                        .code(CODE)
                        .path("/s/" + CODE)
                        .ttlSeconds(86400)
                        .build());

        mockMvc.perform(post("/api/share")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":7,\"ttl\":\"24h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.path").value("/s/" + CODE))
                .andExpect(jsonPath("$.ttlSeconds").value(86400));
    }

    @UnitTest("[单元] GET /api/share/{code}/meta 公开元数据")
    void meta() throws Exception {
        when(shareLinkService.meta(eq(CODE)))
                .thenReturn(ShareMetaResponse.builder()
                        .fileName("a.png")
                        .contentType("image/png")
                        .requiresPin(true)
                        .ttlSecondsRemaining(3600)
                        .build());

        mockMvc.perform(get("/api/share/" + CODE + "/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("a.png"))
                .andExpect(jsonPath("$.requiresPin").value(true));
    }

    @UnitTest("[单元] POST /api/share/{code}/unlock 设置 HttpOnly Cookie")
    void unlockSetsCookie() throws Exception {
        String token = "deadbeefcafebabe";
        when(shareLinkService.unlock(eq(CODE), eq("1234"))).thenReturn(token);

        mockMvc.perform(post("/api/share/" + CODE + "/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString(ShareConstants.COOKIE_GRANT + "=" + token)))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
    }

    @UnitTest("[单元] GET /api/share/{code}/download 返回文件体")
    void download() throws Exception {
        when(shareLinkService.download(eq(CODE), anyString()))
                .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(new ByteArrayResource("hi".getBytes())));

        mockMvc.perform(get("/api/share/" + CODE + "/download")
                        .cookie(new Cookie(ShareConstants.COOKIE_GRANT, "tok")))
                .andExpect(status().isOk())
                .andExpect(content().string("hi"));
    }

    @UnitTest("[单元] GET /api/share/{code}/preview-pdf 返回 PDF 流")
    void previewPdf() throws Exception {
        when(shareLinkService.previewOfficePdf(eq(CODE), anyString()))
                .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(new ByteArrayResource("%PDF-1".getBytes())));

        mockMvc.perform(get("/api/share/" + CODE + "/preview-pdf")
                        .cookie(new Cookie(ShareConstants.COOKIE_GRANT, "tok")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }
}
