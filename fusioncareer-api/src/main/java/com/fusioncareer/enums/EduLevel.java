package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 学历层次枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum EduLevel {

    UNDERGRADUATE(1, "本科生"),
    ACADEMIC_MASTER(2, "学术硕士研究生"),
    PROFESSIONAL_MASTER(3, "专业硕士研究生"),
    DOCTORAL(4, "博士研究生");

    @EnumValue
    private final int code;
    private final String desc;
}
