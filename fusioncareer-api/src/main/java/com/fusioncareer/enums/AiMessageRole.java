package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AiMessageRole {
    USER(1),
    ASSISTANT(2);

    @EnumValue
    private final int code;
}
