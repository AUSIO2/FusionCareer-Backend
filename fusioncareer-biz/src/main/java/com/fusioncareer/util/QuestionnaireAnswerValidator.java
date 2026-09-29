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

import java.util.LinkedHashMap;
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
    private static final int MAX_ANSWERS_LENGTH = 100_000;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_TEXTAREA_LENGTH = 5_000;

    private final ObjectMapper objectMapper;

    public Map<Long, Object> validateDraftAnswers(
            String answersJson, List<JobPostQuestionResponse> questions) {
        return validateAnswers(answersJson, questions, false);
    }

    public Map<Long, Object> validateRequiredAnswers(
            String answersJson, List<JobPostQuestionResponse> questions) {
        return validateAnswers(answersJson, questions, true);
    }

    private Map<Long, Object> validateAnswers(
            String answersJson,
            List<JobPostQuestionResponse> questions,
            boolean requireAnswers) {
        Map<Long, Object> readAnswers = parseAnswerMap(answersJson);
        Map<Long, JobPostQuestionResponse> readQuestions = questions.stream()
                .collect(Collectors.toMap(JobPostQuestionResponse::getId, readQuestion -> readQuestion));
        for (Long readQuestionId : readAnswers.keySet()) {
            if (!readQuestions.containsKey(readQuestionId)) {
                throw invalidAnswer("包含未知题目");
            }
        }
        for (JobPostQuestionResponse readQuestion : questions) {
            Object readValue = readAnswers.get(readQuestion.getId());
            if (requireAnswers && Boolean.TRUE.equals(readQuestion.getRequired()) && !hasValue(readValue)) {
                throw ServiceException.of(QuestionnaireErrorCode.REQUIRED_ANSWERS_INCOMPLETE,
                        "请填写「" + readQuestion.getTitle() + "」");
            }
            if (hasValue(readValue)) {
                validateValue(readQuestion, readValue);
            }
        }
        return readAnswers;
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
        if (answersJson.length() > MAX_ANSWERS_LENGTH) {
            throw invalidAnswer("作答内容过长");
        }
        try {
            List<Map<String, Object>> readItems = objectMapper.readValue(
                    answersJson, new TypeReference<List<Map<String, Object>>>() {});
            Map<Long, Object> createAnswers = new LinkedHashMap<>();
            for (Map<String, Object> readItem : readItems) {
                if (readItem.get("questionId") == null || !readItem.containsKey("value")) {
                    throw invalidAnswer("题目ID或答案缺失");
                }
                Long readQuestionId = Long.valueOf(String.valueOf(readItem.get("questionId")));
                if (createAnswers.containsKey(readQuestionId)) {
                    throw invalidAnswer("题目重复作答");
                }
                createAnswers.put(readQuestionId, readItem.get("value"));
            }
            return createAnswers;
        } catch (ServiceException readError) {
            throw readError;
        } catch (Exception readError) {
            throw invalidAnswer("作答JSON无效");
        }
    }

    private void validateValue(JobPostQuestionResponse readQuestion, Object readValue) {
        if (readQuestion.getQuestionType() == null) {
            throw invalidAnswer("题目类型缺失");
        }
        switch (readQuestion.getQuestionType()) {
            case TEXT -> validateText(readQuestion, readValue, MAX_TEXT_LENGTH);
            case TEXTAREA -> validateText(readQuestion, readValue, MAX_TEXTAREA_LENGTH);
            case RADIO -> validateRadio(readQuestion, readValue);
            case CHECKBOX -> validateCheckbox(readQuestion, readValue);
            case FILE_UPLOAD -> readFileId(readValue);
        }
    }

    private void validateText(JobPostQuestionResponse readQuestion, Object readValue, int readLimit) {
        if (!(readValue instanceof String readText) || readText.trim().length() > readLimit) {
            throw invalidAnswer("「" + readQuestion.getTitle() + "」答案类型或长度不正确");
        }
    }

    private void validateRadio(JobPostQuestionResponse readQuestion, Object readValue) {
        List<String> readOptions = readQuestion.getOptions() == null ? List.of() : readQuestion.getOptions();
        if (!(readValue instanceof String readOption) || !readOptions.contains(readOption)) {
            throw invalidAnswer("「" + readQuestion.getTitle() + "」选项无效");
        }
    }

    private void validateCheckbox(JobPostQuestionResponse readQuestion, Object readValue) {
        List<String> readAllowed = readQuestion.getOptions() == null ? List.of() : readQuestion.getOptions();
        if (!(readValue instanceof List<?> readOptions)
                || readOptions.stream().anyMatch(readOption -> !(readOption instanceof String)
                || !readAllowed.contains(readOption))
                || readOptions.stream().distinct().count() != readOptions.size()) {
            throw invalidAnswer("「" + readQuestion.getTitle() + "」选项无效");
        }
    }

    public Long readFileId(Object readValue) {
        Object readFileId = readValue instanceof Map<?, ?> readFile
                ? readFile.get("fileId") : readValue;
        try {
            return Long.valueOf(String.valueOf(readFileId));
        } catch (RuntimeException readError) {
            throw invalidAnswer("文件答案无效");
        }
    }

    private ServiceException invalidAnswer(String readMessage) {
        return ServiceException.of(QuestionnaireErrorCode.INVALID_ANSWER_FORMAT, readMessage);
    }

    private boolean hasValue(Object readValue) {
        if (readValue == null) {
            return false;
        }
        if (readValue instanceof String readText) {
            return StringUtils.hasText(readText);
        }
        if (readValue instanceof List<?> readList) {
            return !readList.isEmpty() && readList.stream().anyMatch(Objects::nonNull);
        }
        return true;
    }
}
