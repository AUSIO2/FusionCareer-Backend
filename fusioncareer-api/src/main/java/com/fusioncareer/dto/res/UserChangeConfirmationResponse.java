package com.fusioncareer.dto.res;

import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeResourceType;

import java.util.List;

/**
 * 当前登录用户可见的变更确认卡。仅包含安全展示文本，不包含原始快照或密文。
 */
public record UserChangeConfirmationResponse(
        Long actionId,
        ChangeActionType actionType,
        ChangeActionStatus status,
        String title,
        String reason,
        boolean requiresConfirmation,
        List<Change> changes) {

    public record Change(
            ChangeResourceType resourceType,
            String resourceKey,
            String field,
            String label,
            String beforeDisplay,
            String afterDisplay,
            boolean beforeMasked) {
    }
}
