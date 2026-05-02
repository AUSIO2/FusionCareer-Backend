package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal - 岗位信息管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/job-post")
@RequiredArgsConstructor
public class InternalJobPostController {

    private final JobPostService jobPostService;

    @PostMapping
    public R<JobPostResponse> create(@RequestBody JobPostRequest request) {
        return R.success(jobPostService.createJobPost(request));
    }

    @PostMapping("/batch")
    public R<Void> createBatch(@RequestBody List<JobPostRequest> requests) {
        jobPostService.createJobPostBatch(requests);
        return R.success();
    }

    @GetMapping("/{id}")
    public R<JobPostResponse> getById(@PathVariable Long id) {
        return R.success(jobPostService.getJobPost(id));
    }

    @GetMapping("/list")
    public R<PageResult<JobPostResponse>> list(JobPostQueryRequest query) {
        return R.success(jobPostService.listJobPosts(query));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody JobPostRequest request) {
        jobPostService.updateJobPost(id, request);
        return R.success();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        jobPostService.removeById(id);
        return R.success();
    }
}
