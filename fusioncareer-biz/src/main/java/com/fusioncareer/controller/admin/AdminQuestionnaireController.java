package com.fusioncareer.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.req.QuestionnaireReviewRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.QuestionnaireExportService;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

@SaCheckRole("ADMIN")
@RestController
@RequestMapping("/admin/questionnaire")
@RequiredArgsConstructor
@Tag(name = "管理员问卷接口", description = "管理员问卷配置与投递审核")
public class AdminQuestionnaireController {

    private final JobPostQuestionService jobPostQuestionService;
    private final QuestionnaireAnswerService questionnaireAnswerService;
    private final QuestionnaireExportService questionnaireExportService;

    @PostMapping("/questions/batch/{jobPostId}")
    @Operation(summary = "整组保存问卷题目")
    public R<List<JobPostQuestionResponse>> updateQuestions(
            @PathVariable("jobPostId") Long updateJobId,
            @RequestBody List<JobPostQuestionRequest> updateQuestions) {
        return R.success(jobPostQuestionService.saveQuestions(updateJobId, updateQuestions));
    }

    @GetMapping("/questions/{jobPostId}")
    @Operation(summary = "获取问卷题目")
    public R<List<JobPostQuestionResponse>> readQuestions(
            @PathVariable("jobPostId") Long readJobId) {
        return R.success(jobPostQuestionService.listByJobPostId(readJobId));
    }

    @DeleteMapping("/questions/{jobPostId}")
    @Operation(summary = "删除问卷题目")
    public R<Void> deleteQuestions(@PathVariable("jobPostId") Long deleteJobId) {
        jobPostQuestionService.deleteByJobPostId(deleteJobId);
        return R.success();
    }

    @GetMapping("/answers/job/{jobPostId}")
    @Operation(summary = "分页获取岗位投递")
    public R<PageResult<QuestionnaireAnswerResponse>> readAnswers(
            @PathVariable("jobPostId") Long readJobId,
            @RequestParam(name = "page", defaultValue = "1") int readPage,
            @RequestParam(name = "size", defaultValue = "20") int readSize) {
        return R.success(questionnaireAnswerService.listByJobPostId(readJobId, readPage, readSize));
    }

    @GetMapping("/answers/{id}")
    @Operation(summary = "获取投递详情")
    public R<QuestionnaireAnswerResponse> readAnswer(@PathVariable("id") Long readId) {
        return R.success(questionnaireAnswerService.getDetail(readId));
    }

    @PutMapping("/answers/{id}/review")
    @Operation(summary = "审核单条投递")
    public R<QuestionnaireAnswerResponse> reviewAnswer(
            @PathVariable("id") Long updateId,
            @RequestBody QuestionnaireReviewRequest updateReview) {
        return R.success(questionnaireAnswerService.review(
                updateId, updateReview, StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/answers/job/{jobPostId}/review-batch")
    @Operation(summary = "批量审核岗位投递")
    public R<Integer> reviewAnswers(
            @PathVariable("jobPostId") Long updateJobId,
            @RequestBody QuestionnaireReviewRequest updateReview) {
        return R.success(questionnaireAnswerService.reviewBatchByJobPost(
                updateJobId, updateReview, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/answers/job/{jobPostId}/export")
    @Operation(summary = "导出岗位投递")
    public ResponseEntity<byte[]> exportAnswers(
            @PathVariable("jobPostId") Long readJobId,
            @RequestParam(name = "format", defaultValue = "csv") String readFormat,
            @RequestParam(name = "answerIds", required = false) List<Long> readAnswerIds) {
        boolean readZip = "zip".equalsIgnoreCase(readFormat);
        if (!readZip && !"csv".equalsIgnoreCase(readFormat)) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "导出格式仅支持 csv 或 zip");
        }
        byte[] readBody = readZip
                ? questionnaireExportService.buildZip(readJobId, readAnswerIds)
                : questionnaireExportService.buildCsv(readJobId, readAnswerIds);
        String readFilename = "applications-" + readJobId + (readZip ? ".zip" : ".csv");
        MediaType readMediaType = readZip
                ? MediaType.parseMediaType("application/zip")
                : MediaType.parseMediaType("text/csv;charset=UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(readFilename, StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(readMediaType)
                .body(readBody);
    }
}
