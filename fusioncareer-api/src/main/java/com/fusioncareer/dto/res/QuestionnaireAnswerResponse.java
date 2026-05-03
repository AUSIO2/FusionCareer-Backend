package com.fusioncareer.dto.res;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生问卷作答响应 DTO
 *
 * @author Xiong Heng
 */
@Data
public class QuestionnaireAnswerResponse {

    /** 作答记录ID */
    private Long id;

    /** 岗位ID */
    private Long jobPostId;

    /** 学生用户ID */
    private Long userId;

    /** 学生姓名（冗余展示，来自 fc_user.username） */
    private String username;

    /** 学工号（冗余展示，来自 fc_user.student_id） */
    private String studentId;

    /** 作答内容JSON */
    private String answers;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
