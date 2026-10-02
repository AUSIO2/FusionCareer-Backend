package com.fusioncareer.dto.res;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 当前用户的白名单长期记忆。
 */
public record UserMemoryResponse(
        Long version,
        Map<String, Entry> entries,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public record Entry(
            Object value,
            Long sourceMessageId,
            LocalDateTime updatedAt) {
    }
}
