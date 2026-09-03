package com.fusioncareer.dto.res;

import com.fusioncareer.enums.ProfileUpdateStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 简历文件上传结果，以及可选的个人资料同步结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResumeUploadResponse extends ResumeFileResponse {

    private ProfileUpdateStatus profileUpdateStatus;
    private String profileUpdateMessage;
}
