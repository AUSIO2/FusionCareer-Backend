package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.PersonalSpacePatchRequest;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.req.UserChangeRevertResolveRequest;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.PersonalSpaceDocumentsResponse;
import com.fusioncareer.dto.res.PersonalSpaceResponse;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.dto.res.UserMemoryResponse;
import com.fusioncareer.dto.res.UserChangeActionPageResponse;
import com.fusioncareer.dto.res.UserChangeActionResponse;
import com.fusioncareer.dto.res.UserChangeConfirmationResponse;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.service.PersonalSpaceMutationService;
import com.fusioncareer.service.PersonalSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户个人空间接口。
 */
@SaCheckLogin
@RestController
@RequestMapping("/personal-space")
@RequiredArgsConstructor
@Tag(name = "个人空间", description = "当前用户资料、简历、文件与投递的统一入口")
public class PersonalSpaceController {

    private final PersonalSpaceService manageSpace;
    private final PersonalSpaceMutationService mutateSpace;

    @GetMapping
    @Operation(summary = "获取个人空间总览")
    public R<PersonalSpaceResponse> readSpace() {
        return R.success(manageSpace.readSpace(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/profile")
    @Operation(summary = "获取个人空间资料")
    public R<UserProfileResponse> readProfile() {
        return R.success(manageSpace.readProfile(StpUtil.getLoginIdAsLong()));
    }

    @PatchMapping("/profile")
    @Operation(summary = "按字段修改个人空间资料")
    public R<UserProfileResponse> patchProfile(
            @Valid @RequestBody PersonalSpacePatchRequest request) {
        return R.success(mutateSpace.patchProfile(StpUtil.getLoginIdAsLong(), request));
    }

    @GetMapping("/resume")
    @Operation(summary = "获取个人空间结构化简历")
    public R<ResumeResponse> readResume() {
        return R.success(manageSpace.readResume(StpUtil.getLoginIdAsLong()));
    }

    @PatchMapping("/resume")
    @Operation(summary = "按字段修改个人空间结构化简历")
    public R<ResumeResponse> patchResume(
            @Valid @RequestBody PersonalSpacePatchRequest request) {
        return R.success(mutateSpace.patchResume(StpUtil.getLoginIdAsLong(), request));
    }

    @GetMapping("/documents")
    @Operation(summary = "获取个人空间文件与配额")
    public R<PersonalSpaceDocumentsResponse> readDocuments() {
        return R.success(manageSpace.readDocuments(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/documents/deleted")
    @Operation(summary = "获取个人空间已删除文件")
    public R<PersonalSpaceDocumentsResponse> readDeletedDocuments() {
        return R.success(manageSpace.readDeletedDocuments(StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/documents/{fileId}")
    @Operation(summary = "将个人空间文件移入回收站")
    public R<Void> deleteDocument(@PathVariable Long fileId) {
        manageSpace.deleteDocument(StpUtil.getLoginIdAsLong(), fileId);
        return R.success();
    }

    @PostMapping("/documents/{fileId}/restore")
    @Operation(summary = "恢复个人空间文件")
    public R<Void> restoreDocument(@PathVariable Long fileId) {
        manageSpace.restoreDocument(StpUtil.getLoginIdAsLong(), fileId);
        return R.success();
    }

    @GetMapping("/applications")
    @Operation(summary = "分页获取个人空间投递")
    public R<MyQuestionnaireListPageResponse> readApplications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) QuestionnaireSubmissionStatus status) {
        return R.success(manageSpace.readApplications(
                StpUtil.getLoginIdAsLong(), page, size, status));
    }

    @GetMapping("/memory")
    @Operation(summary = "获取个人空间长期记忆")
    public R<UserMemoryResponse> readMemory() {
        return R.success(manageSpace.readMemory(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/memory/{key}")
    @Operation(summary = "设置一个个人空间长期记忆项")
    public R<UserMemoryResponse> saveMemory(
            @PathVariable String key,
            @Valid @RequestBody UserMemoryUpdateRequest request) {
        return R.success(manageSpace.saveMemory(StpUtil.getLoginIdAsLong(), key, request));
    }

    @DeleteMapping("/memory/{key}")
    @Operation(summary = "删除一个个人空间长期记忆项")
    public R<UserMemoryResponse> deleteMemory(
            @PathVariable String key,
            @RequestParam Long expectedVersion) {
        return R.success(manageSpace.deleteMemory(
                StpUtil.getLoginIdAsLong(), key, expectedVersion));
    }

    @DeleteMapping("/memory")
    @Operation(summary = "清空个人空间长期记忆")
    public R<UserMemoryResponse> clearMemory(@RequestParam Long expectedVersion) {
        return R.success(manageSpace.clearMemory(
                StpUtil.getLoginIdAsLong(), expectedVersion));
    }

    @GetMapping("/actions")
    @Operation(summary = "分页获取个人空间变更历史")
    public R<UserChangeActionPageResponse> readActions(
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int size) {
        return R.success(manageSpace.readActions(
                StpUtil.getLoginIdAsLong(), beforeId, size));
    }

    @GetMapping("/actions/{actionId}")
    @Operation(summary = "获取个人空间变更详情")
    public R<UserChangeActionResponse> readAction(@PathVariable Long actionId) {
        return R.success(manageSpace.readAction(
                StpUtil.getLoginIdAsLong(), actionId));
    }

    @GetMapping("/actions/{actionId}/confirmation")
    @Operation(summary = "获取一个变更的安全确认卡")
    public R<UserChangeConfirmationResponse> readConfirmation(@PathVariable Long actionId) {
        return R.success(manageSpace.readConfirmation(
                StpUtil.getLoginIdAsLong(), actionId));
    }

    @PostMapping("/actions/{actionId}/revert")
    @Operation(summary = "创建一个回退提案")
    public R<UserChangeActionResponse> createRevert(@PathVariable Long actionId) {
        return R.success(manageSpace.createRevert(
                StpUtil.getLoginIdAsLong(), actionId));
    }

    @PostMapping("/actions/{actionId}/revert/resolve")
    @Operation(summary = "按用户选择的冲突字段创建回退提案")
    public R<UserChangeActionResponse> createResolvedRevert(
            @PathVariable Long actionId,
            @Valid @RequestBody UserChangeRevertResolveRequest request) {
        return R.success(manageSpace.createResolvedRevert(
                StpUtil.getLoginIdAsLong(), actionId, request));
    }

    @PostMapping("/actions/{actionId}/confirm")
    @Operation(summary = "确认并执行一个待处理提案")
    public R<UserChangeActionResponse> confirmAction(@PathVariable Long actionId) {
        return R.success(manageSpace.confirmAction(
                StpUtil.getLoginIdAsLong(), actionId));
    }

    @PostMapping("/actions/{actionId}/reject")
    @Operation(summary = "拒绝一个待处理提案")
    public R<UserChangeActionResponse> rejectAction(@PathVariable Long actionId) {
        return R.success(manageSpace.rejectAction(
                StpUtil.getLoginIdAsLong(), actionId));
    }
}
