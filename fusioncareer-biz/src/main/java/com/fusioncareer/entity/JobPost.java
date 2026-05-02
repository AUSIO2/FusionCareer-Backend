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
 * <p>
 * 对应表 fc_job_post，包含岗位详情页和筛选页的全部字段。
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_job_post")
public class JobPost implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 岗位ID（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // ==================== 发布来源 ====================

    /** 来源 */
    private SourceType sourceType;

    /** 信息源链接 */
    private String sourceUrl;

    // ==================== 招聘单位 ====================

    /** 单位名称 */
    private String companyName;

    /** 工作部门 */
    private String department;

    /** 工作岗位名称 */
    private String positionName;

    // ==================== 岗位分类 ====================

    /** 岗位大类 */
    private JobCategory jobCategory;

    /** 岗位二级分类 */
    private JobSubCategory jobSubCategory;

    // ==================== 招聘信息 ====================

    /** 招聘类型 */
    private RecruitType recruitType;

    /** 需求人数 */
    private Integer headcount;

    // ==================== 工作要求 ====================

    /** 工作开始时间 */
    private LocalDate workStartDate;

    /** 工作结束时间 */
    private LocalDate workEndDate;

    /** 每周工作天数，如：3（即一周3天及以上） */
    private Integer workDaysPerWeek;

    /** 工作时长类型 */
    private WorkDurationType workDurationType;

    /** 实习时长 */
    private WorkPeriodType workPeriodType;

    /** 工作形式 */
    private WorkMode workMode;

    /** 工作城市 */
    private String workCity;

    /** 工作省份 */
    private String workProvince;

    /** 详细工作地点 */
    private String workLocation;

    /** 薪资下限（元/月） */
    private Integer salaryMin;

    /** 薪资上限（元/月） */
    private Integer salaryMax;

    /** 薪资展示文本，如：面议、150/天 */
    private String salaryDisplay;

    // ==================== 岗位描述 ====================

    /** 岗位职责描述 */
    private String jobDesc;

    // ==================== 招聘要求 ====================

    /** 要求学历层次 */
    private EduLevel reqEduLevel;

    /** 要求专业方向（可多个，逗号分隔） */
    private String reqMajor;

    /** 要求毕业时间，如：2026届 */
    private String reqGradYear;

    /** 技能经验要求 */
    private String reqSkills;

    /** 其他招聘要求 */
    private String reqOther;

    // ==================== 状态与审计 ====================

    /** 岗位状态 */
    private JobPostStatus status;

    /** 发布人 user_id */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
