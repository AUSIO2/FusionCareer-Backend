package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作形式枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum WorkMode {

    ONLINE(1, "线上"),
    OFFLINE(2, "线下"),
    HYBRID(3, "线上线下均可");

    @EnumValue
    private final int code;
    private final String desc;
}
