package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 学生问卷投递状态
 */
@Getter
@AllArgsConstructor
public enum QuestionnaireSubmissionStatus {

    DRAFT(0, "草稿"),
    SUBMITTED(1, "审核中"),
    REVIEWED(2, "已投递");

    @EnumValue
    private final int code;
    private final String label;
}
