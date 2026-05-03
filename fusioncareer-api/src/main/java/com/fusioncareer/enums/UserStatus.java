package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户账号状态枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum UserStatus {

    DISABLED(0, "禁用"),
    NORMAL(1, "正常");

    @EnumValue
    private final int code;
    private final String desc;
}
