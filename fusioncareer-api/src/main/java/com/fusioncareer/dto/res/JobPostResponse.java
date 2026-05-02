package com.fusioncareer.dto.res;

import com.fusioncareer.enums.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class JobPostResponse {
    private Long id;
    private SourceType sourceType;
    private String sourceUrl;
    private String companyName;
    private String department;
    private String positionName;
    private JobCategory jobCategory;
    private JobSubCategory jobSubCategory;
    private RecruitType recruitType;
    private Integer headcount;
    private LocalDate workStartDate;
    private LocalDate workEndDate;
    private Integer workDaysPerWeek;
    private WorkDurationType workDurationType;
    private WorkPeriodType workPeriodType;
    private WorkMode workMode;
    private String workCity;
    private String workProvince;
    private String workLocation;
    private Integer salaryMin;
    private Integer salaryMax;
    private String salaryDisplay;
    private String jobDesc;
    private EduLevel reqEduLevel;
    private String reqMajor;
    private String reqGradYear;
    private String reqSkills;
    private String reqOther;
    private JobPostStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
