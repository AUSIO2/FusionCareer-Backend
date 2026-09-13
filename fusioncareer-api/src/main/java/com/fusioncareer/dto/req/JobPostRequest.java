package com.fusioncareer.dto.req;

import com.fusioncareer.enums.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class JobPostRequest {
    private SourceType sourceType;
    private String sourceUrl;
    private String companyName;
    private String department;
    private String positionName;
    private JobCategory jobCategory;
    private JobSubCategory jobSubCategory;
    private RecruitType recruitType;
    private Integer headcount;
    private String headcountDisplay;
    private LocalDate workStartDate;
    private LocalDate workEndDate;
    private LocalDate applicationDeadline;
    private Integer workDaysPerWeek;
    private String workTimeRequirement;
    private WorkDurationType workDurationType;
    private WorkPeriodType workPeriodType;
    private WorkMode workMode;
    private String workCity;
    private String workProvince;
    private String workLocation;
    private Integer salaryMin;
    private Integer salaryMax;
    private String salaryDisplay;
    private String careerDirection;
    private String jobDesc;
    private EduLevel reqEduLevel;
    private String reqMajor;
    private String reqGradYear;
    private String reqSkills;
    private String reqOther;
    private String internalCompensation;
    private String contactName;
    private String contactInfo;
    private String internalRemark;
    private Boolean recommended;
    private JobPostStatus status;
    private String recycleReason;
}
