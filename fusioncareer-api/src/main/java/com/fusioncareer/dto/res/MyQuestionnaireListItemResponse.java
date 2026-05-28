package com.fusioncareer.dto.res;

import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学生端「我的投递」列表项
 *
 * @author Xiong Heng
 */
@Data
public class MyQuestionnaireListItemResponse {

    private Long id;
    private Long jobPostId;
    private String positionName;
    private String companyName;
    private LocalDate questionnaireDeadline;
    private Boolean expired;
    private String sourceUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private QuestionnaireSubmissionStatus submissionStatus;
    private String statusLabel;
    private LocalDateTime reviewedAt;
    /** 审阅是否通过 */
    private Boolean reviewPassed;
    private String reviewComments;
}
