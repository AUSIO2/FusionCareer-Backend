package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal - 岗位问卷管理接口
 * <p>
 * 管理员通过此接口为岗位配置投递问卷（整组替换），以及查看学生的问卷作答。
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/questionnaire")
@RequiredArgsConstructor
@Tag(name = "内部问卷管理接口", description = "Internal - 问卷配置 & 作答查看")
public class InternalQuestionnaireController {

    private final JobPostQuestionService jobPostQuestionService;
    private final QuestionnaireAnswerService questionnaireAnswerService;

    // ── 问卷题目管理 ──────────────────────────────────────────────────────────

    @PostMapping("/questions/batch/{jobPostId}")
    @Operation(summary = "批量保存问卷（整组替换）")
    public R<List<JobPostQuestionResponse>> saveQuestions(
            @PathVariable Long jobPostId,
            @RequestBody List<JobPostQuestionRequest> questions) {
        return R.success(jobPostQuestionService.saveQuestions(jobPostId, questions));
    }

    @GetMapping("/questions/{jobPostId}")
    @Operation(summary = "获取某岗位的问卷题目列表")
    public R<List<JobPostQuestionResponse>> listQuestions(@PathVariable Long jobPostId) {
        return R.success(jobPostQuestionService.listByJobPostId(jobPostId));
    }

    @DeleteMapping("/questions/{jobPostId}")
    @Operation(summary = "删除某岗位的所有问卷题目")
    public R<Void> deleteQuestions(@PathVariable Long jobPostId) {
        jobPostQuestionService.deleteByJobPostId(jobPostId);
        return R.success();
    }

    // ── 学生作答查看 ──────────────────────────────────────────────────────────

    @GetMapping("/answers/job/{jobPostId}")
    @Operation(summary = "分页查看某岗位的所有问卷作答")
    public R<PageResult<QuestionnaireAnswerResponse>> listAnswers(
            @PathVariable Long jobPostId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.success(questionnaireAnswerService.listByJobPostId(jobPostId, page, size));
    }

    @GetMapping("/answers/{id}")
    @Operation(summary = "查看单条问卷作答详情")
    public R<QuestionnaireAnswerResponse> getAnswerDetail(@PathVariable Long id) {
        return R.success(questionnaireAnswerService.getDetail(id));
    }
}
