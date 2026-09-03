package com.fusioncareer.service;

import com.fusioncareer.enums.ProfileUpdateStatus;

/**
 * 简历识别并同步个人资料的内部处理结果。
 */
public record ProfileSyncOutcome(
        ProfileUpdateStatus status,
        String message
) {
}
