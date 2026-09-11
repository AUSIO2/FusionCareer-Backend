package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.util.QuestionnaireAnswerValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class QuestionnaireAnswerValidatorTest {

    @Test
    void acceptStringEncodedLongQuestionId() {
        JobPostQuestionResponse readQuestion = new JobPostQuestionResponse();
        readQuestion.setId(2030957150604038146L);
        readQuestion.setRequired(true);

        assertThatCode(() -> new QuestionnaireAnswerValidator(new ObjectMapper())
                .validateRequiredAnswers(
                        "[{\"questionId\":\"2030957150604038146\",\"value\":\"ok\"}]",
                        List.of(readQuestion)))
                .doesNotThrowAnyException();
    }
}
