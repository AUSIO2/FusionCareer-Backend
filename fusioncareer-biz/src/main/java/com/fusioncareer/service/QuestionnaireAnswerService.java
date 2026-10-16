package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.QuestionnaireApplicationCount;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;

import java.util.List;
import java.util.Map;

/**
 * 学生问卷作答 Service
 */
public interface QuestionnaireAnswerService extends IService<QuestionnaireAnswerEntity> {

    QuestionnaireAnswerResponse saveDraft(Long userId, QuestionnaireSubmitRequest request);

    QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request);

    /** 校验并计算 Agent 问卷提案，但不写业务表。 */
    PreparedChange prepareProposal(
            Long userId,
            QuestionnaireSubmitRequest request,
            QuestionnaireSubmissionStatus targetStatus);

    /** 查询当前用户指定岗位的作答，包括已由 Revert 软删除的记录。 */
    QuestionnaireAnswerEntity getOwnAnswerIncludingDeleted(Long userId, Long jobPostId);

    /** 由确认/Revert 流程恢复加密快照，不额外记录一条历史。 */
    Long restoreSnapshot(
            Long userId,
            Long jobPostId,
            Long expectedVersion,
            boolean updateExists,
            Map<String, Object> updateFields);

    PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size);

    QuestionnaireAnswerResponse getByUserAndJobPost(Long userId, Long jobPostId);

    QuestionnaireAnswerResponse getDetail(Long id);

    MyQuestionnaireListPageResponse listMyByUserId(Long userId, int page, int size,
                                                    QuestionnaireSubmissionStatus status);

    QuestionnaireApplicationCount countMyApplications(Long userId);

    QuestionnaireAnswerResponse review(Long answerId, QuestionnaireReviewRequest request, Long reviewedBy);

    int reviewBatchByJobPost(Long jobPostId, QuestionnaireReviewRequest request, Long reviewedBy);

    record PreparedChange(
            String resourceKey,
            ChangeOperation operation,
            List<String> changedFields,
            Long expectedVersion,
            boolean beforeExists,
            boolean afterExists,
            Map<String, Object> beforeFields,
            Map<String, Object> afterFields) {
    }
}
