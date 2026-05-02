package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端 - 岗位浏览 & 筛选接口
 *
 * @author Xiong Heng
 */
@SaCheckLogin
@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobPostController {

    private final JobPostService jobPostService;

    @GetMapping("/{id}")
    public R<JobPostResponse> getById(@PathVariable Long id) {
        return R.success(jobPostService.getJobPost(id));
    }

    @GetMapping("/list")
    public R<PageResult<JobPostResponse>> list(JobPostQueryRequest query) {
        return R.success(jobPostService.listPublishedJobPosts(query));
    }
}
