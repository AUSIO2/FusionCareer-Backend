package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    @Transactional
    @Override
    public void patchProfile(Long userId, UserProfileRequest request) {
        LambdaUpdateWrapper<UserProfileEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(UserProfileEntity::getUserId, userId)
                .set(request.getRealName() != null, UserProfileEntity::getRealName, request.getRealName())
                .set(request.getGender() != null, UserProfileEntity::getGender, request.getGender())
                .set(request.getBirthDate() != null, UserProfileEntity::getBirthDate, request.getBirthDate())
                .set(request.getPoliticalStatus() != null, UserProfileEntity::getPoliticalStatus,
                        request.getPoliticalStatus())
                .set(request.getPhone() != null, UserProfileEntity::getPhone, request.getPhone())
                .set(request.getEmail() != null, UserProfileEntity::getEmail, request.getEmail())
                .set(request.getWechat() != null, UserProfileEntity::getWechat, request.getWechat())
                .set(request.getHometown() != null, UserProfileEntity::getHometown, request.getHometown())
                .set(request.getGrade() != null, UserProfileEntity::getGrade, request.getGrade())
                .set(request.getMajor() != null, UserProfileEntity::getMajor, request.getMajor())
                .set(request.getEduLevel() != null, UserProfileEntity::getEduLevel, request.getEduLevel())
                .set(request.getSupervisor() != null, UserProfileEntity::getSupervisor, request.getSupervisor());

        if (!update(updateWrapper)) {
            UserProfileEntity entity = new UserProfileEntity();
            BeanUtils.copyProperties(request, entity);
            entity.setUserId(userId);
            save(entity);
        }
    }

    @Override
    public PageResult<UserProfileResponse> listProfiles(int page, int size) {
        Page<UserProfileEntity> result = page(new Page<>(page, size),
                new LambdaQueryWrapper<UserProfileEntity>().orderByDesc(UserProfileEntity::getCreatedAt));

        PageResult<UserProfileResponse> pageResult = new PageResult<>(result.getTotal(), page, size);
        result.getRecords().forEach(e -> pageResult.add(toResponse(e)));
        return pageResult;
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
