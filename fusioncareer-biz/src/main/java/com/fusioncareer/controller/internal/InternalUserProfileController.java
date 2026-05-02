package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Internal - 用户资料管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/user-profile")
@RequiredArgsConstructor
@Tag(name = "内部用户资料管理接口", description = "Internal - 用户资料管理接口")
public class InternalUserProfileController {

    private final UserProfileService userProfileService;

    @PostMapping("/{userId}")
    @Operation(summary = "创建或更新用户资料")
    public R<Void> create(@PathVariable Long userId, @RequestBody UserProfileRequest request) {
        userProfileService.saveOrUpdateProfile(userId, request);
        return R.success();
    }

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户资料详情")
    public R<UserProfileResponse> getByUserId(@PathVariable Long userId) {
        return R.success(userProfileService.getProfile(userId));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询用户资料列表")
    public R<PageResult<UserProfileResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.success(userProfileService.listProfiles(page, size));
    }

    @PutMapping("/{userId}")
    @Operation(summary = "更新用户资料信息")
    public R<Void> update(@PathVariable Long userId, @RequestBody UserProfileRequest request) {
        userProfileService.updateProfile(userId, request);
        return R.success();
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户资料")
    public R<Void> delete(@PathVariable Long userId) {
        userProfileService.removeById(userId);
        return R.success();
    }
}
