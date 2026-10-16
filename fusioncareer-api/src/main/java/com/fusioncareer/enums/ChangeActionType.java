package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChangeActionType {
    APPLY(1),
    REVERT(2);

    @EnumValue
    private final int code;
}
