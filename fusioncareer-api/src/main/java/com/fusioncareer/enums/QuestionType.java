package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 问卷题目类型枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum QuestionType {

    TEXT(1, "单行文本"),
    TEXTAREA(2, "多行文本"),
    RADIO(3, "单选"),
    CHECKBOX(4, "多选"),
    FILE_UPLOAD(5, "文件上传");

    @EnumValue
    private final int code;
    private final String desc;
}
