package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;

import java.util.List;

public interface JobPostService extends IService<JobPostEntity> {

    JobPostResponse createJobPost(JobPostRequest request);

    void createJobPostBatch(List<JobPostRequest> requests);

    JobPostResponse getJobPost(Long id);

    PageResult<JobPostResponse> listJobPosts(JobPostQueryRequest query);

    PageResult<JobPostResponse> listJobs(JobPostQueryRequest query);

    PageResult<JobPostResponse> listPublishedJobPosts(JobPostQueryRequest query);

    void updateJobPost(Long id, JobPostRequest request);
}
