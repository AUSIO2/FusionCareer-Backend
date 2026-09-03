package com.fusioncareer.dto.res;

import com.fusioncareer.enums.ProfileUpdateStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 简历文件上传结果，以及可选的个人资料同步结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResumeUploadResponse extends ResumeFileResponse {

    private ProfileUpdateStatus profileUpdateStatus;
    private List<String> updatedFields = new ArrayList<>();
    private String profileUpdateMessage;
}
