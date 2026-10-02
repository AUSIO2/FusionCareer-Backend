package com.fusioncareer.dto.res;

import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeActorType;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 不包含加密快照和值的安全变更历史投影。
 */
public record UserChangeActionResponse(
        Long id,
        ChangeActorType actorType,
        String origin,
        ChangeActionType actionType,
        Long revertsActionId,
        ChangeActionStatus status,
        String reason,
        LocalDateTime appliedAt,
        LocalDateTime createdAt,
        List<Item> items) {

    public record Item(
            Long id,
            ChangeResourceType resourceType,
            String resourceKey,
            ChangeOperation operation,
            List<String> changedFields,
            Long expectedVersion,
            Long appliedVersion) {
    }
}
