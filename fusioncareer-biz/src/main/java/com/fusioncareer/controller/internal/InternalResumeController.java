package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Internal - 用户简历管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/resume")
@RequiredArgsConstructor
@Tag(name = "内部简历管理接口", description = "Internal - 用户简历管理接口")
public class InternalResumeController {

    private final ResumeService resumeService;

    @PostMapping("/{userId}")
    @Operation(summary = "创建或更新简历")
    public R<Void> create(@PathVariable Long userId, @RequestBody ResumeRequest request) {
        resumeService.saveOrUpdateResume(userId, request);
        return R.success();
    }

    @GetMapping("/{userId}")
    @Operation(summary = "获取简历详情")
    public R<ResumeResponse> getByUserId(@PathVariable Long userId) {
        return R.success(resumeService.getResume(userId));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询简历列表")
    public R<PageResult<ResumeResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.success(resumeService.listResumes(page, size));
    }

    @PutMapping("/{userId}")
    @Operation(summary = "更新简历信息")
    public R<Void> update(@PathVariable Long userId, @RequestBody ResumeRequest request) {
        resumeService.updateResume(userId, request);
        return R.success();
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除简历")
    public R<Void> delete(@PathVariable Long userId) {
        resumeService.removeById(userId);
        return R.success();
    }
}
