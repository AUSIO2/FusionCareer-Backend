package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 岗位状态枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum JobPostStatus {

    OFFLINE(0, "已下线"),
    PUBLISHED(1, "发布中"),
    EXPIRED(2, "已截止"),
    RECYCLED(3, "回收站");

    @EnumValue
    private final int code;
    private final String desc;
}
