package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.entity.UserProfileEntity;

public interface UserProfileService extends IService<UserProfileEntity> {

    UserProfileResponse getProfile(Long userId);

    void saveOrUpdateProfile(Long userId, UserProfileRequest request);

    PageResult<UserProfileResponse> listProfiles(int page, int size);

    void updateProfile(Long userId, UserProfileRequest request);
}
