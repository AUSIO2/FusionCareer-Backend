package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 招聘类型枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum RecruitType {

    BIG_INTERNSHIP(1, "大实习"),
    SMALL_INTERNSHIP(2, "小实习"),
    DAILY_INTERNSHIP(3, "日常实习"),
    CAMPUS_RECRUITMENT(4, "应届生招聘"),
    CAMPUS_SCREENING(5, "应届生摸排"),
    OTHER(6, "其他");

    @EnumValue
    private final int code;
    private final String desc;
}
