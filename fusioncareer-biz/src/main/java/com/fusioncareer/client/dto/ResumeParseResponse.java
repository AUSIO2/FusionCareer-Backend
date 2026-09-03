package com.fusioncareer.client.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Python 简历解析服务响应。
 */
@Data
public class ResumeParseResponse {

    private Integer code;
    private String message;
    private ResumeProfileData data;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ResumeProfileData {
        private String realName;
        private Integer gender;
        private String birthDate;
        private Integer politicalStatus;
        private String phone;
        private String email;
        private String wechat;
        private String hometown;
        private String grade;
        private String major;
        private Integer eduLevel;
        private String supervisor;
    }
}
