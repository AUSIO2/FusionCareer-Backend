package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.mapper.UserMapper;
import com.fusioncareer.mapper.UserProfileMapper;
import com.fusioncareer.service.UserProfileService;
import cn.hutool.core.bean.BeanUtil;
import com.fusioncareer.util.PersonNameUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl extends ServiceImpl<UserProfileMapper, UserProfileEntity> implements UserProfileService {

    private final UserMapper readUserMapper;

    @Override
    public UserProfileResponse getProfile(Long userId) {
        return toResponse(getById(userId));
    }

    @Transactional
    @Override
    public void saveOrUpdateProfile(Long userId, UserProfileRequest request) {
        UserProfileEntity entity = BeanUtil.copyProperties(request, UserProfileEntity.class);
        entity.setUserId(userId);
        UserProfileEntity readProfile = getById(userId);
        if (readProfile == null) {
            entity.setVersion(0L);
            save(entity);
            return;
        }
        entity.setVersion(readProfile.getVersion());
        if (!updateById(entity)) {
            throw ServiceException.of(ResultCode.CONFLICT, "个人资料已发生变化，请刷新后重试");
        }
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
        UserProfileEntity entity = BeanUtil.copyProperties(request, UserProfileEntity.class);
        entity.setUserId(userId);
        UserProfileEntity readProfile = getById(userId);
        if (readProfile == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "个人资料不存在");
        }
        entity.setVersion(readProfile.getVersion());
        if (!updateById(entity)) {
            throw ServiceException.of(ResultCode.CONFLICT, "个人资料已发生变化，请刷新后重试");
        }
    }

    private UserProfileResponse toResponse(UserProfileEntity readProfile) {
        if (readProfile == null) return null;
        UserEntity readUser = readUserMapper.selectById(readProfile.getUserId());
        UserProfileResponse readResponse = BeanUtil.copyProperties(readProfile, UserProfileResponse.class);
        readResponse.setRealName(PersonNameUtil.readName(
                readUser == null ? null : readUser.getStudentId(), readProfile.getRealName(),
                readUser == null ? null : readUser.getUsername()));
        return readResponse;
    }
}
