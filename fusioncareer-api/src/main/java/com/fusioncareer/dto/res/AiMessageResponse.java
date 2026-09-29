package com.fusioncareer.dto.res;

import com.fusioncareer.enums.AiMessageRole;
import com.fusioncareer.enums.AiMessageStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AiMessageResponse(
        Long id,
        Long epoch,
        String runId,
        String requestId,
        AiMessageRole role,
        AiMessageStatus status,
        String content,
        List<Long> fileIds,
        String model,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        String errorCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
