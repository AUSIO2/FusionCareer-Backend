package com.fusioncareer.dto;

import lombok.Data;

/**
 * 当前用户各问卷投递状态数量。
 */
@Data
public class QuestionnaireApplicationCount {
    private long total;
    private long draft;
    private long pending;
    private long done;
}
