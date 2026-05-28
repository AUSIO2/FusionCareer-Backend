package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.JobPostQuestionnaireResponse;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.exception.QuestionnaireErrorCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.util.QuestionnaireDeadlineUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 学生端 - 岗位投递问卷接口
 */
@SaCheckLogin
@RestController
@RequestMapping("/questionnaire")
@RequiredArgsConstructor
@Tag(name = "岗位投递问卷接口", description = "学生端 - 查看问卷 & 提交作答")
public class QuestionnaireController {

    private final JobPostQuestionService jobPostQuestionService;
    private final JobPostService jobPostService;
    private final QuestionnaireAnswerService questionnaireAnswerService;
    private final ResumeFileService resumeFileService;

    @GetMapping("/questions/{jobPostId}")
    @Operation(summary = "获取某岗位的投递问卷（含截止信息）")
    public R<JobPostQuestionnaireResponse> getQuestions(@PathVariable Long jobPostId) {
        JobPostEntity job = jobPostService.getById(jobPostId);
        if (job == null) {
            throw ServiceException.of(QuestionnaireErrorCode.JOB_POST_NOT_FOUND);
        }
        JobPostQuestionnaireResponse resp = new JobPostQuestionnaireResponse();
        resp.setQuestionnaireDeadline(job.getWorkEndDate());
        resp.setExpired(QuestionnaireDeadlineUtil.isExpired(job.getWorkEndDate()));
        resp.setSourceUrl(job.getSourceUrl());
        resp.setQuestions(jobPostQuestionService.listByJobPostId(jobPostId));
        return R.success(resp);
    }

    @PostMapping("/draft")
    @Operation(summary = "保存问卷草稿")
    public R<QuestionnaireAnswerResponse> saveDraft(@RequestBody QuestionnaireSubmitRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(questionnaireAnswerService.saveDraft(userId, request));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交问卷作答（重复提交覆盖更新）")
    public R<QuestionnaireAnswerResponse> submit(@RequestBody QuestionnaireSubmitRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(questionnaireAnswerService.submit(userId, request));
    }

    @GetMapping("/my/list")
    @Operation(summary = "分页查看我的问卷投递记录")
    public R<MyQuestionnaireListPageResponse> listMy(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) QuestionnaireSubmissionStatus status) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(questionnaireAnswerService.listMyByUserId(userId, page, size, status));
    }

    @GetMapping("/my/{jobPostId}")
    @Operation(summary = "查看我对某岗位的问卷作答")
    public R<QuestionnaireAnswerResponse> getMy(@PathVariable Long jobPostId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(questionnaireAnswerService.getByUserAndJobPost(userId, jobPostId));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传问卷附件（复用简历文件上传）")
    public R<ResumeFileResponse> uploadFile(@RequestParam("file") MultipartFile file) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(resumeFileService.upload(userId, file));
    }
}
