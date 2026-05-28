package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;

/**
 * 学生问卷作答 Service
 */
public interface QuestionnaireAnswerService extends IService<QuestionnaireAnswerEntity> {

    QuestionnaireAnswerResponse saveDraft(Long userId, QuestionnaireSubmitRequest request);

    QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request);

    PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size);

    QuestionnaireAnswerResponse getByUserAndJobPost(Long userId, Long jobPostId);

    QuestionnaireAnswerResponse getDetail(Long id);

    MyQuestionnaireListPageResponse listMyByUserId(Long userId, int page, int size,
                                                    QuestionnaireSubmissionStatus status);

    QuestionnaireAnswerResponse review(Long answerId, QuestionnaireReviewRequest request, Long reviewedBy);

    int reviewBatchByJobPost(Long jobPostId, QuestionnaireReviewRequest request, Long reviewedBy);
}
