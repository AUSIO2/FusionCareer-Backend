package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 每周工作天数类型枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum WorkDurationType {

    ONE_TO_TWO_DAYS(1, "一周1-2天"),
    THREE_TO_FOUR_DAYS(2, "一周3-4天"),
    FIVE_DAYS(3, "一周5天");

    @EnumValue
    private final int code;
    private final String desc;
}
