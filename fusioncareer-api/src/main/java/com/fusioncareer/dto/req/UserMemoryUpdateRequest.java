package com.fusioncareer.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设置一个长期记忆项。
 */
public record UserMemoryUpdateRequest(
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能小于 0")
        Long expectedVersion,

        @NotNull(message = "value 不能为空")
        Object value) {
}
