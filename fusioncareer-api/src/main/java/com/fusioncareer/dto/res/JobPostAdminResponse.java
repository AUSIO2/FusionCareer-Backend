package com.fusioncareer.dto.res;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 管理员岗位详情，包含不对学生公开的征集信息。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobPostAdminResponse extends JobPostResponse {
    private String workTimeRequirement;
    private String careerDirection;
    private String internalCompensation;
    private String contactName;
    private String contactInfo;
    private String internalRemark;
}
