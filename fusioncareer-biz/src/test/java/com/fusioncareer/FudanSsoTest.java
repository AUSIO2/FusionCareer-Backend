package com.fusioncareer;

import cn.dev33.satoken.stp.StpInterface;
import com.fusioncareer.config.FudanOAuth2Properties;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.FudanSsoService;
import com.fusioncareer.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FudanSsoTest {

    @Autowired
    private MockMvc readMockMvc;

    @Autowired
    private FudanSsoService readSsoService;

    @Autowired
    private FudanOAuth2Properties readSsoProperties;

    @Autowired
    private UserService readUserService;

    @Autowired
    private StpInterface readRoleProvider;

    @MockBean
    private RestTemplate readRestClient;

    @Test
    void createState() {
        String createFirstState = readSsoService.createState();
        String createSecondState = readSsoService.createState();
        String readUrl = readSsoService.buildLoginUrl(createFirstState);

        assertThat(createFirstState).isNotBlank().isNotEqualTo(createSecondState);
        assertThat(readUrl).contains("state=" + createFirstState);
        assertThat(readUrl).contains("client_id=test-client");
        assertThat(readUrl).contains("redirect_uri=http://127.0.0.1:19100/fudan/callback");
    }

    @Test
    void rejectState() throws Exception {
        MvcResult readLogin = readMockMvc.perform(get("/fudan/login"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession updateSession = (MockHttpSession) readLogin.getRequest().getSession(false);
        String readState = readState(readLogin);

        readMockMvc.perform(get("/fudan/callback")
                        .param("code", "test-code")
                        .param("state", "wrong-state")
                        .session(updateSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error?msg=sso_login_failed"));

        readMockMvc.perform(get("/fudan/callback")
                        .param("code", "test-code")
                        .param("state", readState)
                        .session(updateSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error?msg=sso_login_failed"));
        verifyNoInteractions(readRestClient);
    }

    @Test
    void hideToken() throws Exception {
        String readUrl = loginUser("test-hide");
        assertThat(readUrl).startsWith("http://127.0.0.1:5173/#/login?token=");
        assertThat(readUrl.substring(0, readUrl.indexOf('#'))).doesNotContain("token=");
    }

    @Test
    void logoutSession() throws Exception {
        String readUrl = loginUser("test-logout");
        String readToken = readUrl.substring(readUrl.indexOf("token=") + 6);

        readMockMvc.perform(post("/fudan/logout").header("Fusion-Token", readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.redirectUrl").isNotEmpty());
        readMockMvc.perform(get("/user/profile/get").header("Fusion-Token", readToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readUser() throws Exception {
        UserEntity createUser = createUser("test-normal", UserRole.NORMAL, UserStatus.NORMAL);
        String readUrl = loginUser("test-normal");
        String readToken = readUrl.substring(readUrl.indexOf("token=") + 6);

        readMockMvc.perform(get("/user/me").header("Fusion-Token", readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(createUser.getId()))
                .andExpect(jsonPath("$.data.role").value("NORMAL"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"));
    }

    @Test
    void readRoles() {
        UserEntity createUser = createUser("test-admin", UserRole.ADMIN, UserStatus.NORMAL);

        assertThat(readRoleProvider.getRoleList(createUser.getId(), "login"))
                .containsExactly("ADMIN");
    }

    @Test
    void rejectDisabled() throws Exception {
        createUser("test-disabled", UserRole.ADMIN, UserStatus.DISABLED);

        assertThat(loginUser("test-disabled"))
                .isEqualTo("/error?msg=sso_login_failed");
    }

    private String readState(MvcResult readLogin) {
        String readUrl = readLogin.getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(readUrl).build()
                .getQueryParams().getFirst("state");
    }

    private String loginUser(String readStudentId) throws Exception {
        mockIdentity(readStudentId);
        MvcResult readLogin = readMockMvc.perform(get("/fudan/login")).andReturn();
        MockHttpSession updateSession = (MockHttpSession) readLogin.getRequest().getSession(false);
        MvcResult readCallback = readMockMvc.perform(get("/fudan/callback")
                        .param("code", "test-code")
                        .param("state", readState(readLogin))
                        .session(updateSession))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return readCallback.getResponse().getRedirectedUrl();
    }

    private UserEntity createUser(String createStudentId, UserRole createRole, UserStatus createStatus) {
        UserEntity createUser = new UserEntity();
        createUser.setUsername(createStudentId);
        createUser.setStudentId(createStudentId);
        createUser.setRole(createRole);
        createUser.setStatus(createStatus);
        readUserService.save(createUser);
        return createUser;
    }

    private void mockIdentity(String readStudentId) {
        when(readRestClient.exchange(
                eq(readSsoProperties.getTokenUrl()),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"test-access-token\"}"));
        when(readRestClient.exchange(
                eq(readSsoProperties.getUserInfoUrl()),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"userId\":\"" + readStudentId
                        + "\",\"userName\":\"Test User\"}"));
    }
}
