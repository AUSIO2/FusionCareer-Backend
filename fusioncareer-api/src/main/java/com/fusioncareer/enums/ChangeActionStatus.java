package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChangeActionStatus {
    PENDING(1),
    APPLIED(2),
    REJECTED(3),
    SUPERSEDED(4),
    CONFLICT(5),
    FAILED(6);

    @EnumValue
    private final int code;
}
