package com.fusioncareer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InternalTokenTest {

    @Autowired
    private MockMvc readMockMvc;

    @Test
    void rejectInternal() throws Exception {
        readMockMvc.perform(get("/internal/job-post/list"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void allowInternal() throws Exception {
        readMockMvc.perform(get("/internal/job-post/list")
                        .header("X-Internal-Token", "test-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
