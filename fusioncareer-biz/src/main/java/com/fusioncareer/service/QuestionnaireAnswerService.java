package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;

/**
 * 学生问卷作答 Service
 *
 * @author Xiong Heng
 */
public interface QuestionnaireAnswerService extends IService<QuestionnaireAnswerEntity> {

    /**
     * 提交/覆盖问卷作答
     * <p>
     * 如果该学生已对该岗位提交过，则覆盖更新。
     *
     * @param userId  当前登录用户ID
     * @param request 作答请求
     * @return 作答记录
     */
    QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request);

    /**
     * 管理员：分页查看某岗位的所有问卷作答
     *
     * @param jobPostId 岗位ID
     * @param page      页码
     * @param size      每页大小
     * @return 分页结果
     */
    PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size);

    /**
     * 学生：查看自己对某岗位的作答记录
     *
     * @param userId    当前用户ID
     * @param jobPostId 岗位ID
     * @return 作答记录，不存在则返回 null
     */
    QuestionnaireAnswerResponse getByUserAndJobPost(Long userId, Long jobPostId);

    /**
     * 管理员：查看单条作答详情
     *
     * @param id 作答记录ID
     * @return 作答详情
     */
    QuestionnaireAnswerResponse getDetail(Long id);
}
