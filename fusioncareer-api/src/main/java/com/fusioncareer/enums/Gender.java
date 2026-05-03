package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 性别枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum Gender {

    MALE(1, "男"),
    FEMALE(2, "女"),
    OTHER(3, "其他");

    @EnumValue
    private final int code;
    private final String desc;
}
