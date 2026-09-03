package com.fusioncareer.enums;

/**
 * 上传简历后同步个人资料的处理状态。
 */
public enum ProfileUpdateStatus {
    NOT_REQUESTED,
    SUCCESS,
    NO_FIELDS_RECOGNIZED,
    ALGORITHM_FAILED,
    PROFILE_UPDATE_FAILED
}
