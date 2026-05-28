package com.fusioncareer.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 问卷投递模块业务错误码（段：4200xx）
 */
@Getter
@AllArgsConstructor
public enum QuestionnaireErrorCode implements IErrorCode {

    JOB_POST_NOT_FOUND(42001, "岗位不存在"),
    QUESTIONNAIRE_DEADLINE_PASSED(42002, "问卷已截止，无法提交或修改"),
    REQUIRED_ANSWERS_INCOMPLETE(42003, "必填题目未填写完整"),
    INVALID_SUBMISSION_STATUS(42004, "当前投递状态不允许此操作"),
    REVIEW_COMMENTS_REQUIRED(42005, "审阅意见不能为空"),
    ALREADY_REVIEWED(42006, "该投递已审阅，不可重复审阅"),
    REVIEW_PASSED_REQUIRED(42007, "请指定审阅是否通过");

    private final int code;
    private final String message;
}
