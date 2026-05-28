package com.fusioncareer.dto.res;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 某岗位问卷题目及截止信息
 *
 * @author Xiong Heng
 */
@Data
public class JobPostQuestionnaireResponse {

    private LocalDate questionnaireDeadline;
    private Boolean expired;
    private String sourceUrl;
    private List<JobPostQuestionResponse> questions;
}
