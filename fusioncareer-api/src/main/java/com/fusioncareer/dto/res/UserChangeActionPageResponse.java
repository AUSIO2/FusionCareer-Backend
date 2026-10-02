package com.fusioncareer.dto.res;

import java.util.List;

public record UserChangeActionPageResponse(
        List<UserChangeActionResponse> actions,
        Long nextBeforeId,
        boolean hasMore) {
}
