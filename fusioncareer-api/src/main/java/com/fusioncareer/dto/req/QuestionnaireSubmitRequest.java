package com.fusioncareer.dto.req;

import lombok.Data;

/**
 * 学生问卷作答请求 DTO
 *
 * @author Xiong Heng
 */
@Data
public class QuestionnaireSubmitRequest {

    /** 投递的岗位ID */
    private Long jobPostId;

    /**
     * 作答内容JSON字符串
     * <p>
     * 格式示例：
     * <pre>
     * [
     *   { "questionId": 1001, "value": "2022级" },
     *   { "questionId": 1002, "value": "男" },
     *   { "questionId": 1003, "value": "resume_file_id_12345" }
     * ]
     * </pre>
     */
    private String answers;
}
