package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 岗位信息来源枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum SourceType {

    PLATFORM(1, "平台发布"),
    CRAWL(2, "就业资讯源爬取");

    @EnumValue
    private final int code;
    private final String desc;
}
