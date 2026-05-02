package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端 - 个人资料 & 简历接口
 *
 * @author Xiong Heng
 */
@SaCheckLogin
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;
    private final ResumeService resumeService;

    @GetMapping("/profile")
    public R<UserProfileResponse> getProfile() {
        return R.success(userProfileService.getProfile(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/profile")
    public R<Void> saveProfile(@RequestBody UserProfileRequest request) {
        userProfileService.saveOrUpdateProfile(StpUtil.getLoginIdAsLong(), request);
        return R.success();
    }

    @GetMapping("/resume")
    public R<ResumeResponse> getResume() {
        return R.success(resumeService.getResume(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping("/resume")
    public R<Void> saveResume(@RequestBody ResumeRequest request) {
        resumeService.saveOrUpdateResume(StpUtil.getLoginIdAsLong(), request);
        return R.success();
    }
}
