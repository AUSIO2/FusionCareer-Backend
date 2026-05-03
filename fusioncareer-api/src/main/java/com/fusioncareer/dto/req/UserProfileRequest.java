package com.fusioncareer.dto.req;

import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.Mindset;
import com.fusioncareer.enums.PoliticalStatus;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileRequest {
    private String realName;
    private Gender gender;
    private LocalDate birthDate;
    private PoliticalStatus politicalStatus;
    private String phone;
    private String email;
    private String wechat;
    private String hometown;
    private String grade;
    private String major;
    private EduLevel eduLevel;
    private String supervisor;
    private String intentionOrder;
    private String intentionCity;
    private String intentionDream;
    private Mindset mindset;
}
