package com.fusioncareer.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

@SaCheckRole("SUPERADMIN")
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
@Tag(name = "系统管理用户接口", description = "仅超级管理员可查询用户资料、调整角色和导出")
public class AdminUserController {

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ResumeService resumeService;
    private final ResumeFileService resumeFileService;
    private final UserExportService userExportService;

    @GetMapping("/list")
    @Operation(summary = "分页查询用户列表")
    public R<PageResult<UserResponse>> readUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) UserRole role) {
        return R.success(userService.listUsers(page, size, username, role));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询用户账号信息")
    public R<UserResponse> readUser(@PathVariable Long id) {
        return R.success(requireUser(id));
    }

    @GetMapping("/{id}/profile")
    @Operation(summary = "查询指定用户资料", description = "用户存在但尚未填写资料时 data 为 null")
    public R<UserProfileResponse> readProfile(@PathVariable Long id) {
        requireUser(id);
        return R.success(userProfileService.getProfile(id));
    }

    @GetMapping("/{id}/resume")
    @Operation(summary = "查询指定用户简历正文", description = "用户存在但尚未填写简历时 data 为 null")
    public R<ResumeResponse> readResume(@PathVariable Long id) {
        requireUser(id);
        return R.success(resumeService.getResume(id));
    }

    @GetMapping("/{id}/resume/file/list")
    @Operation(summary = "查询指定用户的简历文件列表")
    public R<List<ResumeFileResponse>> readResumeFiles(@PathVariable Long id) {
        requireUser(id);
        return R.success(resumeFileService.listByUser(id));
    }

    @GetMapping("/{id}/resume/file/{fileId}/download")
    @Operation(summary = "下载指定用户的简历文件")
    public ResponseEntity<Resource> downloadResume(@PathVariable Long id, @PathVariable Long fileId) {
        requireUser(id);
        ResumeFileEntity file = resumeFileService.getById(fileId);
        if (file == null || !id.equals(file.getUserId())) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "该用户的简历文件不存在");
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.getMimeType());
        } catch (Exception error) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(mediaType)
                .body(resumeFileService.loadAsResource(file.getStoragePath()));
    }

    @GetMapping("/export")
    @Operation(summary = "导出用户信息 Excel", description = "按用户名/学号、角色及选中用户ID筛选，包含账号、资料、简历正文三个工作表")
    public ResponseEntity<byte[]> exportUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) List<Long> userIds) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("用户信息.xlsx", StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(userExportService.exportUsers(username, role, userIds));
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "修改用户角色")
    public R<UserResponse> updateRole(
            @PathVariable("id") Long updateId,
            @RequestParam UserRole role) {
        if (role != UserRole.SUPERADMIN && updateId.equals(StpUtil.getLoginIdAsLong())) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "不能撤销自己的超级管理员权限");
        }
        return R.success(userService.updateRole(updateId, role));
    }

    private UserResponse requireUser(Long id) {
        UserResponse user = userService.getUserById(id);
        if (user == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
