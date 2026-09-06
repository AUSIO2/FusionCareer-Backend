package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.service.JobDescriptionNormalizationService;
import com.fusioncareer.service.JobPostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Internal - 岗位信息管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/job-post")
@RequiredArgsConstructor
@Tag(name = "内部岗位管理接口", description = "Internal - 岗位信息管理接口")
public class InternalJobPostController {

    private final JobPostService jobPostService;
    private final JobDescriptionNormalizationService jobDescriptionNormalizationService;

    @PostMapping("/normalize")
    @Operation(summary = "将原始岗位描述转换为标准岗位信息")
    public R<JobPostRequest> normalize(@Valid @RequestBody JobDescriptionNormalizeRequest request) {
        return R.success(jobDescriptionNormalizationService.normalize(request.getRawDescription()));
    }

    @PostMapping
    @Operation(summary = "创建岗位")
    public R<JobPostResponse> create(@RequestBody JobPostRequest request) {
        return R.success(jobPostService.createJobPost(request));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量创建岗位")
    public R<Void> createBatch(@RequestBody List<JobPostRequest> requests) {
        jobPostService.createJobPostBatch(requests);
        return R.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取岗位详情")
    public R<JobPostResponse> getById(@PathVariable Long id) {
        return R.success(jobPostService.getJobPost(id));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询岗位列表")
    public R<PageResult<JobPostResponse>> list(JobPostQueryRequest query) {
        return R.success(jobPostService.listJobPosts(query));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新岗位信息")
    public R<Void> update(@PathVariable Long id, @RequestBody JobPostRequest request) {
        jobPostService.updateJobPost(id, request);
        return R.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除岗位")
    public R<Void> delete(@PathVariable Long id) {
        jobPostService.removeById(id);
        return R.success();
    }
}
