package com.fusioncareer.dto.res;

import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
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

    /** 账号用户名（兼容旧客户端） */
    private String username;

    /** 展示姓名，优先用户资料；缺失或为学工号时返回 null */
    private String realName;

    /** 学工号（冗余展示，来自 fc_user.student_id） */
    private String studentId;

    /** 作答内容JSON */
    private String answers;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    private QuestionnaireSubmissionStatus submissionStatus;
    private String statusLabel;
    private LocalDateTime reviewedAt;
    /** 审阅是否通过 */
    private Boolean reviewPassed;
    private String reviewComments;
    private Long version;
}
