package com.fusioncareer.dto.req;

import lombok.Data;

@Data
public class ResumeRequest {
    private String personalIntro;
    private String basicInfo;
    private String education;
    private String internship;
    private String campus;
    private String awards;
    private String skills;
    private String portfolio;
    private String remark;
}
