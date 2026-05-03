package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户端 - 岗位浏览 & 筛选接口
 *
 * @author Xiong Heng
 */
@SaCheckLogin
@RestController
@RequestMapping("/job")
@RequiredArgsConstructor
@Tag(name = "岗位接口", description = "用户端 - 岗位浏览 & 筛选接口")
public class JobPostController {

    private final JobPostService jobPostService;

    @GetMapping("/{id}")
    @Operation(summary = "获取岗位详情")
    public R<JobPostResponse> getById(@PathVariable Long id) {
        return R.success(jobPostService.getJobPost(id));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询岗位列表")
    public R<PageResult<JobPostResponse>> list(JobPostQueryRequest query) {
        return R.success(jobPostService.listPublishedJobPosts(query));
    }
}
