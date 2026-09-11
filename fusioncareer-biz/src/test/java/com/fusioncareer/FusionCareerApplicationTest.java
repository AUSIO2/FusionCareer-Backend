package com.fusioncareer;

import com.fusioncareer.config.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FusionCareerApplicationTest {

    @Autowired
    private MockMvc readMockMvc;

    @Autowired
    private UploadProperties readUploadProperties;

    @Test
    void startContext() {
        assertThat(readMockMvc).isNotNull();
    }

    @Test
    void readHealth() throws Exception {
        readMockMvc.perform(get("/sys/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void rejectGuest() throws Exception {
        readMockMvc.perform(get("/user/profile/get"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void returnError() throws Exception {
        readMockMvc.perform(get("/fudan/callback"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void readUploadPath() {
        Path readUpload = Path.of(readUploadProperties.getBaseDir()).toAbsolutePath().normalize();
        assertThat(readUpload.toString()).doesNotContain("/data/fusioncareer/uploads");
        assertThat(readUpload.toString()).containsAnyOf("fusioncareer-test-uploads", ".test-output/uploads");
    }
}
