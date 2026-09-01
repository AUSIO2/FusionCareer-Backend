package com.fusioncareer.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.service.JobPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SaCheckRole("ADMIN")
@RestController
@RequestMapping("/admin/job-post")
@RequiredArgsConstructor
@Tag(name = "管理员岗位接口", description = "管理员岗位信息管理")
public class AdminJobPostController {

    private final JobPostService jobPostService;

    @PostMapping
    @Operation(summary = "创建岗位")
    public R<JobPostResponse> createJobPost(@RequestBody JobPostRequest createRequest) {
        return R.success(jobPostService.createJobPost(createRequest));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量创建岗位")
    public R<Void> createJobPosts(@RequestBody List<JobPostRequest> createRequests) {
        jobPostService.createJobPostBatch(createRequests);
        return R.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取岗位详情")
    public R<JobPostResponse> readJobPost(@PathVariable("id") Long readId) {
        return R.success(jobPostService.getJobPost(readId));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询岗位列表")
    public R<PageResult<JobPostResponse>> readJobPosts(JobPostQueryRequest readQuery) {
        return R.success(jobPostService.listJobPosts(readQuery));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新岗位")
    public R<Void> updateJobPost(@PathVariable("id") Long updateId,
                                 @RequestBody JobPostRequest updateRequest) {
        jobPostService.updateJobPost(updateId, updateRequest);
        return R.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除岗位")
    public R<Void> deleteJobPost(@PathVariable("id") Long deleteId) {
        jobPostService.removeById(deleteId);
        return R.success();
    }
}
