package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Internal - 用户简历管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/resume")
@RequiredArgsConstructor
public class InternalResumeController {

    private final ResumeService resumeService;

    @PostMapping("/{userId}")
    public R<Void> create(@PathVariable Long userId, @RequestBody ResumeRequest request) {
        resumeService.saveOrUpdateResume(userId, request);
        return R.success();
    }

    @GetMapping("/{userId}")
    public R<ResumeResponse> getByUserId(@PathVariable Long userId) {
        return R.success(resumeService.getResume(userId));
    }

    @GetMapping("/list")
    public R<PageResult<ResumeResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.success(resumeService.listResumes(page, size));
    }

    @PutMapping("/{userId}")
    public R<Void> update(@PathVariable Long userId, @RequestBody ResumeRequest request) {
        resumeService.updateResume(userId, request);
        return R.success();
    }

    @DeleteMapping("/{userId}")
    public R<Void> delete(@PathVariable Long userId) {
        resumeService.removeById(userId);
        return R.success();
    }
}
