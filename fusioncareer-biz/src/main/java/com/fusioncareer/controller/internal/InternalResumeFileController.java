package com.fusioncareer.controller.internal;

import com.fusioncareer.common.R;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.service.ResumeFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Internal - 简历文件访问接口（供 Python AI 服务调用）
 * <p>
 * 路径前缀 {@code /internal/**} 已在 {@link com.fusioncareer.config.SaTokenConfigure}
 * 中排除鉴权，Python 服务可直接调用，无需携带登录态。
 * <p>
 * ⚠️ 该接口仅限内网使用，生产环境请通过防火墙/网关限制访问来源 IP。
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/resume-file")
@RequiredArgsConstructor
@Tag(name = "内部简历文件接口", description = "Internal - 供 Python AI 服务访问用户简历文件")
public class InternalResumeFileController {

    private final ResumeFileService resumeFileService;

    /**
     * 代替指定用户上传简历文件（内部服务/管理后台调用）
     */
    @PostMapping(value = "/{userId}/upload", consumes = "multipart/form-data")
    @Operation(summary = "代替用户上传简历文件",
            description = "内部服务代替指定用户上传文件，复用同样的校验和配额逻辑")
    public R<ResumeFileResponse> upload(
            @Parameter(description = "用户ID", required = true) @PathVariable Long userId,
            @Parameter(description = "简历文件", required = true) @RequestParam("file") MultipartFile file) {
        return R.success(resumeFileService.upload(userId, file));
    }

    /**
     * 获取指定用户上传的所有简历文件元数据
     * <p>
     * Python 可先调此接口获取文件列表，再按需下载具体文件。
     */
    @GetMapping("/{userId}/list")
    @Operation(summary = "获取用户简历文件列表",
            description = "返回该用户所有上传文件的元数据（含 fileId、原始文件名、MIME 类型、大小）")
    public R<List<ResumeFileResponse>> listByUser(
            @Parameter(description = "用户ID", required = true) @PathVariable Long userId) {
        return R.success(resumeFileService.listByUser(userId));
    }

    /**
     * 下载指定简历文件（流式返回，不校验用户归属）
     * <p>
     * Python 传入 fileId 即可获取文件字节流，适合直接读取 PDF/图片内容进行 AI 解析。
     */
    @GetMapping("/{fileId}/download")
    @Operation(summary = "下载指定简历文件（internal，不鉴权归属）",
            description = "直接按 fileId 返回文件流，Python 服务无需登录态即可调用")
    public ResponseEntity<Resource> download(
            @Parameter(description = "文件ID", required = true) @PathVariable Long fileId) {

        // internal 接口跳过用户归属校验，直接按 ID 查询
        ResumeFileEntity entity = resumeFileService.getById(fileId);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = resumeFileService.loadAsResource(entity.getStoragePath());

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(entity.getMimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(entity.getOriginalName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaType)
                .body(resource);
    }
}
