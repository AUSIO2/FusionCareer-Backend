package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 目前心态枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum Mindset {

    CONFIDENT(1, "比较有把握"),
    CAUTIOUSLY_OPTIMISTIC(2, "谨慎乐观"),
    LACK_OF_CONFIDENCE(3, "信心不足"),
    VERY_ANXIOUS(4, "非常焦虑"),
    ZEN_WAITING(5, "佛系等待"),

    DAME(9, "完蛋了");

    @EnumValue
    private final int code;
    private final String desc;
}
