package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum UserRole {

    NORMAL(0, "普通用户"),
    ADMIN(1, "管理员");

    @EnumValue
    private final int code;
    private final String desc;
}
