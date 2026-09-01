package com.fusioncareer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class FudanDevLoginTest {

    @Autowired
    private MockMvc readMockMvc;

    @Test
    void loginMock() throws Exception {
        String readStudentToken = readToken("NORMAL");
        readMockMvc.perform(get("/user/me").header("Fusion-Token", readStudentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("dev-student"))
                .andExpect(jsonPath("$.data.role").value("NORMAL"));

        String readAdminToken = readToken("ADMIN");
        readMockMvc.perform(get("/user/me").header("Fusion-Token", readAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("dev-admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        readMockMvc.perform(post("/fudan/logout").header("Fusion-Token", readAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redirectUrl")
                        .value("http://127.0.0.1:5173/#/login"));
        readMockMvc.perform(get("/user/me").header("Fusion-Token", readAdminToken))
                .andExpect(status().isUnauthorized());
    }

    private String readToken(String readRole) throws Exception {
        MvcResult readLogin = readMockMvc.perform(get("/fudan/login").param("role", readRole))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String readUrl = readLogin.getResponse().getRedirectedUrl();
        return readUrl.substring(readUrl.indexOf("token=") + 6);
    }
}
