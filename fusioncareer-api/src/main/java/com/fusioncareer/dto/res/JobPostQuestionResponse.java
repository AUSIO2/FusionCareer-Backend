package com.fusioncareer.dto.res;

import com.fusioncareer.enums.QuestionType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问卷题目响应 DTO
 *
 * @author Xiong Heng
 */
@Data
public class JobPostQuestionResponse {

    /** 问题ID */
    private Long id;

    /** 所属岗位ID */
    private Long jobPostId;

    /** 排序序号 */
    private Integer sortOrder;

    /** 问题标题 */
    private String title;

    /** 题目类型 */
    private QuestionType questionType;

    /** 选项列表（单选/多选时使用） */
    private List<String> options;

    /** 是否必填 */
    private Boolean required;

    /** 输入提示文字 */
    private String placeholder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
