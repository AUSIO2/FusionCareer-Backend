package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AiMessageStatus {
    PENDING(0),
    STREAMING(1),
    COMPLETED(2),
    FAILED(3),
    CANCELLED(4);

    @EnumValue
    private final int code;
}
