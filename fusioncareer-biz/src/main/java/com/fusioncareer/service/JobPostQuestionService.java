package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.entity.JobPostQuestionEntity;

import java.util.List;

/**
 * 岗位投递问卷题目 Service
 *
 * @author Xiong Heng
 */
public interface JobPostQuestionService extends IService<JobPostQuestionEntity> {

    /**
     * 批量保存某岗位的问卷（整组替换：先删后插）
     *
     * @param jobPostId 岗位ID
     * @param questions 问题列表
     * @return 保存后的问题列表
     */
    List<JobPostQuestionResponse> saveQuestions(Long jobPostId, List<JobPostQuestionRequest> questions);

    /**
     * 获取某岗位的所有问卷题目（按 sortOrder 升序）
     *
     * @param jobPostId 岗位ID
     * @return 问题列表
     */
    List<JobPostQuestionResponse> listByJobPostId(Long jobPostId);

    /**
     * 删除某岗位的所有问卷题目
     *
     * @param jobPostId 岗位ID
     */
    void deleteByJobPostId(Long jobPostId);
}
