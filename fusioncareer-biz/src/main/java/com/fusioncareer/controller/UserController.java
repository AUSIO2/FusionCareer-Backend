package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 用户端 - 个人资料 & 简历接口
 *
 * @author Xiong Heng
 */
@SaCheckLogin
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "用户基础接口", description = "用户端 - 个人资料 & 简历接口")
public class UserController {

    private final UserProfileService userProfileService;
    private final ResumeService resumeService;
    private final ResumeFileService resumeFileService;
    private final UploadProperties uploadProperties;
    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户和角色")
    public R<UserResponse> readUser() {
        return R.success(userService.getUserById(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/profile/get")
    @Operation(summary = "获取个人资料")
    public R<UserProfileResponse> getProfile() {
        return R.success(userProfileService.getProfile(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/profile/save")
    @Operation(summary = "保存个人资料")
    public R<Void> saveProfile(@RequestBody UserProfileRequest request) {
        userProfileService.saveOrUpdateProfile(StpUtil.getLoginIdAsLong(), request);
        return R.success();
    }

    @GetMapping("/resume/get")
    @Operation(summary = "获取个人简历")
    public R<ResumeResponse> getResume() {
        return R.success(resumeService.getResume(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/resume/save")
    @Operation(summary = "保存个人简历")
    public R<Void> saveResume(@RequestBody ResumeRequest request) {
        resumeService.saveOrUpdateResume(StpUtil.getLoginIdAsLong(), request);
        return R.success();
    }

    // ── 简历文件上传 ──────────────────────────────────────────────────────────

    @PostMapping(value = "/resume/file/upload", consumes = "multipart/form-data")
    @Operation(summary = "上传简历文件",
            description = "支持 PDF / JPG / PNG，单文件 ≤ 20MB，个人总配额 30MB")
    public R<ResumeFileResponse> uploadResumeFile(
            @Parameter(description = "简历文件", required = true)
            @RequestParam("file") MultipartFile file) {
        return R.success(resumeFileService.upload(StpUtil.getLoginIdAsLong(), file));
    }

    @GetMapping("/resume/file/list")
    @Operation(summary = "获取我上传的简历文件列表")
    public R<List<ResumeFileResponse>> listResumeFiles() {
        return R.success(resumeFileService.listByUser(StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/resume/file/{fileId}")
    @Operation(summary = "删除指定简历文件")
    public R<Void> deleteResumeFile(@PathVariable Long fileId) {
        resumeFileService.delete(StpUtil.getLoginIdAsLong(), fileId);
        return R.success();
    }

    @GetMapping("/resume/file/{fileId}/download")
    @Operation(summary = "下载指定简历文件",
            description = "只能下载自己上传的文件，会触发浏览器下载对话框")
    public ResponseEntity<Resource> downloadResumeFile(
            @Parameter(description = "文件ID", required = true) @PathVariable Long fileId) {

        long userId = StpUtil.getLoginIdAsLong();
        // 1. 鉴权查询：仅返回属于当前用户的文件
        ResumeFileEntity entity = resumeFileService.getOwnFile(userId, fileId);

        // 2. 加载文件资源
        Resource resource = resumeFileService.loadAsResource(entity.getStoragePath());

        // 3. 构建响应头
        // RFC 5987 编码文件名，支持中文
        String encodedName = URLEncoder.encode(entity.getOriginalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(entity.getOriginalName(), StandardCharsets.UTF_8)
                .build();

        // 从存储的 MIME 类型推断 MediaType
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(entity.getMimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaType)
                .body(resource);
    }

    @GetMapping("/resume/file/quota")
    @Operation(summary = "查询简历文件存储配额",
            description = "返回 usedBytes（已用）和 quotaBytes（总配额）")
    public R<Map<String, Long>> getResumeFileQuota() {
        long used = resumeFileService.getUsedBytes(StpUtil.getLoginIdAsLong());
        long quota = uploadProperties.getQuotaPerUser();
        return R.success(Map.of("usedBytes", used, "quotaBytes", quota));
    }
}
