package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.util.QuestionnaireAnswerValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionnaireAnswerValidatorTest {

    private final QuestionnaireAnswerValidator validateAnswers =
            new QuestionnaireAnswerValidator(new ObjectMapper());

    @Test
    void acceptStringEncodedLongQuestionId() {
        JobPostQuestionResponse readQuestion = new JobPostQuestionResponse();
        readQuestion.setId(2030957150604038146L);
        readQuestion.setRequired(true);
        readQuestion.setQuestionType(QuestionType.TEXT);

        assertThatCode(() -> validateAnswers.validateRequiredAnswers(
                        "[{\"questionId\":\"2030957150604038146\",\"value\":\"ok\"}]",
                        List.of(readQuestion)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectUnknownQuestion() {
        assertThatThrownBy(() -> validateAnswers.validateDraftAnswers(
                "[{\"questionId\":2,\"value\":\"ok\"}]",
                List.of(question(1L, QuestionType.TEXT, List.of()))))
                .hasMessageContaining("未知题目");
    }

    @Test
    void rejectDuplicateQuestion() {
        assertThatThrownBy(() -> validateAnswers.validateDraftAnswers(
                "[{\"questionId\":1,\"value\":\"甲\"},{\"questionId\":1,\"value\":\"乙\"}]",
                List.of(question(1L, QuestionType.TEXT, List.of()))))
                .hasMessageContaining("重复作答");
    }

    @Test
    void rejectInvalidOption() {
        assertThatThrownBy(() -> validateAnswers.validateDraftAnswers(
                "[{\"questionId\":1,\"value\":\"乙\"}]",
                List.of(question(1L, QuestionType.RADIO, List.of("甲")))))
                .hasMessageContaining("选项无效");
    }

    @Test
    void acceptFileObject() {
        Map<Long, Object> readAnswers = validateAnswers.validateDraftAnswers(
                "[{\"questionId\":1,\"value\":{\"fileId\":\"42\"}}]",
                List.of(question(1L, QuestionType.FILE_UPLOAD, List.of())));

        assertThat(validateAnswers.readFileId(readAnswers.get(1L))).isEqualTo(42L);
    }

    @Test
    void rejectFileObjectWithUnknownFields() {
        assertThatThrownBy(() -> validateAnswers.validateDraftAnswers(
                "[{\"questionId\":1,\"value\":{\"fileId\":\"42\",\"userId\":\"7\"}}]",
                List.of(question(1L, QuestionType.FILE_UPLOAD, List.of()))))
                .hasMessageContaining("文件答案无效");
    }

    private JobPostQuestionResponse question(Long readId, QuestionType readType, List<String> readOptions) {
        JobPostQuestionResponse createQuestion = new JobPostQuestionResponse();
        createQuestion.setId(readId);
        createQuestion.setTitle("测试题目");
        createQuestion.setQuestionType(readType);
        createQuestion.setOptions(readOptions);
        return createQuestion;
    }
}
