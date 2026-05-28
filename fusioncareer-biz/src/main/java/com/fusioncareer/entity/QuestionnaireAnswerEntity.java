package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 学生问卷作答实体
 */
@Data
@TableName("fc_questionnaire_answer")
public class QuestionnaireAnswerEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long jobPostId;
    private Long userId;
    private String answers;
    private QuestionnaireSubmissionStatus submissionStatus;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;
    /** 审阅是否通过 */
    private Boolean reviewPassed;
    private String reviewComments;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
