package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实习总时长类型枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum WorkPeriodType {

    LESS_THAN_THREE_MONTHS(1, "3个月以内"),
    THREE_TO_SIX_MONTHS(2, "3-6个月"),
    MORE_THAN_SIX_MONTHS(3, "6个月以上");

    @EnumValue
    private final int code;
    private final String desc;
}
