package com.fusioncareer.dto.res;

import java.util.List;

public record AiMessagePageResponse(
        List<AiMessageResponse> messages,
        Long nextBeforeId,
        boolean hasMore) {
}
