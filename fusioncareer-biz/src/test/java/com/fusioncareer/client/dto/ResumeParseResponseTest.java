package com.fusioncareer.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeParseResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsSnakeCaseAlgorithmPayloadAndNumericStrings() throws Exception {
        String json = """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "real_name": "张三",
                    "political_status": "3",
                    "edu_level": "2",
                    "birth_date": "2000-01-15"
                  }
                }
                """;

        ResumeParseResponse response = objectMapper.readValue(json, ResumeParseResponse.class);

        assertThat(response.getData().getRealName()).isEqualTo("张三");
        assertThat(response.getData().getPoliticalStatus()).isEqualTo(3);
        assertThat(response.getData().getEduLevel()).isEqualTo(2);
        assertThat(response.getData().getBirthDate()).isEqualTo("2000-01-15");
    }
}
