package com.fusioncareer.dto.req;

import lombok.Data;

/**
 * 管理员审阅问卷投递请求
 */
@Data
public class QuestionnaireReviewRequest {

    /** 是否通过审阅 */
    private Boolean passed;

    /** 审阅意见，必填 */
    private String comments;
}
