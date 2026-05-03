package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.ResumeFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 学生端 - 岗位投递问卷接口
 * <p>
 * 学生查看问卷、上传文件、提交问卷作答。
 *
 * @author Xiong Heng
 */
@SaCheckLogin
@RestController
@RequestMapping("/questionnaire")
@RequiredArgsConstructor
@Tag(name = "岗位投递问卷接口", description = "学生端 - 查看问卷 & 提交作答")
public class QuestionnaireController {

    private final JobPostQuestionService jobPostQuestionService;
    private final QuestionnaireAnswerService questionnaireAnswerService;
    private final ResumeFileService resumeFileService;

    @GetMapping("/questions/{jobPostId}")
    @Operation(summary = "获取某岗位的投递问卷")
    public R<List<JobPostQuestionResponse>> getQuestions(@PathVariable Long jobPostId) {
        return R.success(jobPostQuestionService.listByJobPostId(jobPostId));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交问卷作答（重复提交覆盖更新）")
    public R<QuestionnaireAnswerResponse> submit(@RequestBody QuestionnaireSubmitRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.success(questionnaireAnswerService.submit(userId, request));
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
