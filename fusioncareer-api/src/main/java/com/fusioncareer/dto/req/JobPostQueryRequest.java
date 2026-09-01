package com.fusioncareer.dto.req;

import com.fusioncareer.enums.*;
import lombok.Data;

/**
 * 岗位筛选查询条件
 *
 * @author Xiong Heng
 */
@Data
public class JobPostQueryRequest {

    private int page = 1;
    private int size = 10;
    private JobCategory jobCategory;
    private JobSubCategory jobSubCategory;
    private RecruitType recruitType;
    private WorkDurationType workDurationType;
    private WorkPeriodType workPeriodType;
    private WorkMode workMode;
    private String workProvince;
    private String workCity;
    private Integer salaryMin;
    private Integer salaryMax;
    private JobPostSort sortBy = JobPostSort.NEWEST;
    private Boolean recommended;
    private JobPostStatus status;
    private SourceType sourceType;
    private String keyword;
}
