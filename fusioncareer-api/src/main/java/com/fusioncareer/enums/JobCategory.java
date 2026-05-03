package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 岗位大类枚举（一级分类）
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum JobCategory {

    ACADEMIC(1, "学术教职"),
    GOVERNMENT(2, "党政机关"),
    MEDIA(3, "新闻媒体"),
    ENTERPRISE(4, "企业公司"),
    OTHER(9, " 其他");

    @EnumValue
    private final int code;
    private final String desc;
}
