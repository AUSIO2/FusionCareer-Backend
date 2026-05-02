package com.fusioncareer.dto.res;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResumeResponse {
    private Long userId;
    private String personalIntro;
    private String basicInfo;
    private String education;
    private String internship;
    private String campus;
    private String awards;
    private String skills;
    private String portfolio;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
