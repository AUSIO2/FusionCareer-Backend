package com.fusioncareer.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.exception.QuestionnaireErrorCode;
import com.fusioncareer.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 问卷作答 JSON 校验
 */
@Component
@RequiredArgsConstructor
public class QuestionnaireAnswerValidator {

    private static final int MAX_REVIEW_COMMENTS_LENGTH = 2000;

    private final ObjectMapper objectMapper;

    public void validateRequiredAnswers(String answersJson, List<JobPostQuestionResponse> questions) {
        Map<Long, Object> answerMap = parseAnswerMap(answersJson);
        for (JobPostQuestionResponse q : questions) {
            if (!Boolean.TRUE.equals(q.getRequired())) {
                continue;
            }
            Object value = answerMap.get(q.getId());
            if (!hasValue(value)) {
                throw ServiceException.of(QuestionnaireErrorCode.REQUIRED_ANSWERS_INCOMPLETE,
                        "请填写「" + q.getTitle() + "」");
            }
        }
    }

    public boolean requireReviewPassed(QuestionnaireReviewRequest request) {
        if (request == null || request.getPassed() == null) {
            throw ServiceException.of(QuestionnaireErrorCode.REVIEW_PASSED_REQUIRED);
        }
        return request.getPassed();
    }

    public String normalizeReviewComments(String comments) {
        if (!StringUtils.hasText(comments)) {
            throw ServiceException.of(QuestionnaireErrorCode.REVIEW_COMMENTS_REQUIRED);
        }
        String trimmed = comments.trim();
        if (trimmed.length() > MAX_REVIEW_COMMENTS_LENGTH) {
            throw ServiceException.of(QuestionnaireErrorCode.REVIEW_COMMENTS_REQUIRED,
                    "审阅意见不能超过 " + MAX_REVIEW_COMMENTS_LENGTH + " 字");
        }
        return trimmed;
    }

    private Map<Long, Object> parseAnswerMap(String answersJson) {
        if (!StringUtils.hasText(answersJson)) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                    answersJson, new TypeReference<List<Map<String, Object>>>() {});
            return items.stream()
                    .filter(item -> item.get("questionId") != null)
                    .collect(Collectors.toMap(
                            item -> ((Number) item.get("questionId")).longValue(),
                            item -> item.get("value"),
                            (a, b) -> b
                    ));
        } catch (Exception e) {
            throw ServiceException.of(QuestionnaireErrorCode.REQUIRED_ANSWERS_INCOMPLETE, "作答格式不正确");
        }
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return StringUtils.hasText(s);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty() && list.stream().anyMatch(Objects::nonNull);
        }
        return true;
    }
}
