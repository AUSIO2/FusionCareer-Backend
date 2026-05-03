package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 岗位二级分类枚举
 * <p>
 * 编码规则：个位数归属学术教职，十位段(11-16)归属党政机关，
 * 二十位段(21-24)归属新闻媒体，三十位段(31-33)归属企业公司。
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum JobSubCategory {

    // ---- 学术教职 ----
    FURTHER_STUDY(1, JobCategory.ACADEMIC, "升学深造"),
    TEACHING_POSITION(2, JobCategory.ACADEMIC, "考取教职"),
    MIDDLE_SCHOOL_TEACHER(3, JobCategory.ACADEMIC, "中学教师"),

    // ---- 党政机关 ----
    SELECTED_GRADUATE(11, JobCategory.GOVERNMENT, "选调生"),
    CIVIL_SERVANT(12, JobCategory.GOVERNMENT, "公务员"),
    UNIVERSITY_ADMIN(13, JobCategory.GOVERNMENT, "高校行政"),
    HOSPITAL(14, JobCategory.GOVERNMENT, "医院"),
    BANK(15, JobCategory.GOVERNMENT, "银行"),
    OTHER_PUBLIC_INSTITUTION(16, JobCategory.GOVERNMENT, "其他事业单位"),

    // ---- 新闻媒体 ----
    CENTRAL_MEDIA(21, JobCategory.MEDIA, "党报央媒"),
    REGIONAL_MEDIA(22, JobCategory.MEDIA, "地区主流媒体"),
    OTHER_MEDIA(23, JobCategory.MEDIA, "其他媒体机构"),
    SELF_MEDIA(24, JobCategory.MEDIA, "自媒体"),

    // ---- 企业公司 ----
    STATE_OWNED(31, JobCategory.ENTERPRISE, "国央企"),
    PRIVATE_ENTERPRISE(32, JobCategory.ENTERPRISE, "民企"),
    FOREIGN_ENTERPRISE(33, JobCategory.ENTERPRISE, "外企"),

    OTHER(99, JobCategory.OTHER, "其他");

    @EnumValue
    private final int code;
    private final JobCategory parent;
    private final String desc;
}
