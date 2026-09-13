package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 岗位信息实体
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_job_post")
public class JobPostEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
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
    private LocalDateTime recycledAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
