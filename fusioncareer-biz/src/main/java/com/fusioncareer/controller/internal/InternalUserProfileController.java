package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Internal - 用户资料管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/user-profile")
@RequiredArgsConstructor
public class InternalUserProfileController {

    private final UserProfileService userProfileService;

    @PostMapping("/{userId}")
    public R<Void> create(@PathVariable Long userId, @RequestBody UserProfileRequest request) {
        userProfileService.saveOrUpdateProfile(userId, request);
        return R.success();
    }

    @GetMapping("/{userId}")
    public R<UserProfileResponse> getByUserId(@PathVariable Long userId) {
        return R.success(userProfileService.getProfile(userId));
    }

    @GetMapping("/list")
    public R<PageResult<UserProfileResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.success(userProfileService.listProfiles(page, size));
    }

    @PutMapping("/{userId}")
    public R<Void> update(@PathVariable Long userId, @RequestBody UserProfileRequest request) {
        userProfileService.updateProfile(userId, request);
        return R.success();
    }

    @DeleteMapping("/{userId}")
    public R<Void> delete(@PathVariable Long userId) {
        userProfileService.removeById(userId);
        return R.success();
    }
}
