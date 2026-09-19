package com.fusioncareer;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.entity.JobPostQuestionEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.impl.JobPostQuestionServiceImpl;
import com.fusioncareer.service.impl.QuestionnaireAnswerServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BeanMappingTest {
    @Test
    void questionOptionsKeepJsonDeserialization() {
        JobPostQuestionEntity entity = BeanUtil.toBean(Map.of(
                "id", 42L, "title", "选择", "options", "[\"甲\",\"乙\"]"), JobPostQuestionEntity.class);
        JobPostQuestionResponse response = ReflectionTestUtils.invokeMethod(
                new JobPostQuestionServiceImpl(new ObjectMapper()), "toResponse", entity);
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getTitle()).isEqualTo("选择");
        assertThat(response.getOptions()).containsExactly("甲", "乙");
    }

    @Test
    void userEnrichmentPreservesAnswerId() {
        UserService users = mock(UserService.class);
        when(users.getById(7L)).thenReturn(BeanUtil.toBean(Map.of(
                "id", 7L, "username", "姓名", "studentId", "2026001"), UserEntity.class));
        QuestionnaireAnswerResponse response = BeanUtil.toBean(
                Map.of("id", 42L, "userId", 7L), QuestionnaireAnswerResponse.class);
        ReflectionTestUtils.invokeMethod(new QuestionnaireAnswerServiceImpl(users, null, null, null),
                "enrichUserInfo", response, 7L);
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getUsername()).isEqualTo("姓名");
        assertThat(response.getStudentId()).isEqualTo("2026001");
    }
}
