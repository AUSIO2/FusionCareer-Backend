package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.mapper.UserProfileMapper;
import com.fusioncareer.service.UserProfileService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
public class UserProfileServiceImpl extends ServiceImpl<UserProfileMapper, UserProfileEntity> implements UserProfileService {

    @Override
    public UserProfileResponse getProfile(Long userId) {
        return toResponse(getById(userId));
    }

    @Transactional
    @Override
    public void saveOrUpdateProfile(Long userId, UserProfileRequest request) {
        UserProfileEntity entity = new UserProfileEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        saveOrUpdate(entity);
    }

    @Override
    public PageResult<UserProfileResponse> listProfiles(int page, int size) {
        Page<UserProfileEntity> readProfiles = page(createPage(page, size),
                new LambdaQueryWrapper<UserProfileEntity>().orderByDesc(UserProfileEntity::getCreatedAt));

        PageResult<UserProfileResponse> readPage = new PageResult<>(readProfiles.getTotal(),
                (int) readProfiles.getCurrent(), (int) readProfiles.getSize());
        readProfiles.getRecords().forEach(e -> readPage.add(toResponse(e)));
        return readPage;
    }

    @Transactional
    @Override
    public void updateProfile(Long userId, UserProfileRequest request) {
        UserProfileEntity entity = new UserProfileEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        updateById(entity);
    }

    private UserProfileResponse toResponse(UserProfileEntity entity) {
        if (entity == null) return null;
        UserProfileResponse resp = new UserProfileResponse();
        BeanUtils.copyProperties(entity, resp);
        return resp;
    }
}
