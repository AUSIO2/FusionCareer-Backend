package com.fusioncareer.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 政治面貌枚举
 *
 * @author Xiong Heng
 */
@Getter
@AllArgsConstructor
public enum PoliticalStatus {

    MASSES(1, "群众"),
    LEAGUE_MEMBER(2, "共青团员"),
    PARTY_MEMBER(3, "中共党员"),
    OTHER(4, "其他");

    @EnumValue
    private final int code;
    private final String desc;
}
