package com.fusioncareer.dto.res;

import java.time.LocalDateTime;

public record AiSessionResponse(
        boolean exists,
        Long epoch,
        boolean activeRun,
        String activeRunId,
        LocalDateTime leaseUntil,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
