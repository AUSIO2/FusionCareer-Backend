package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.UserMapper;
import com.fusioncareer.mapper.UserProfileMapper;
import com.fusioncareer.service.UserService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fusioncareer.util.PaginationUtil.createPage;
import static com.fusioncareer.util.PersonNameUtil.readName;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements UserService {

    private final UserProfileMapper userProfileMapper;

    @Transactional
    @Override
    public UserResponse createUser(UserRequest request) {
        UserEntity entity = BeanUtil.copyProperties(request, UserEntity.class);
        save(entity);
        return getUserById(entity.getId());
    }

    @Override
    public UserResponse getUserById(Long id) {
        return toResponse(getById(id), userProfileMapper.selectById(id));
    }

    @Override
    public PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role) {
        return listUsers(page, size, username, role, null);
    }

    @Override
    public PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role, List<Long> userIds) {
        List<Long> readNameUserIds = StringUtils.hasText(username)
                ? userProfileMapper.selectList(new LambdaQueryWrapper<UserProfileEntity>()
                        .like(UserProfileEntity::getRealName, username)).stream()
                        .map(UserProfileEntity::getUserId).toList()
                : List.of();
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(StringUtils.hasText(username), readQuery -> readQuery
                       .like(UserEntity::getUsername, username)
                       .or()
                       .like(UserEntity::getStudentId, username)
                       .or(!readNameUserIds.isEmpty())
                       .in(!readNameUserIds.isEmpty(), UserEntity::getId, readNameUserIds))
               .eq(role != null, UserEntity::getRole, role)
               .in(userIds != null && !userIds.isEmpty(), UserEntity::getId, userIds)
               .orderByDesc(UserEntity::getCreatedAt)
               .orderByDesc(UserEntity::getId);

        Page<UserEntity> readUsers = page(createPage(page, size), wrapper);

        PageResult<UserResponse> readPage = new PageResult<>(readUsers.getTotal(),
                (int) readUsers.getCurrent(), (int) readUsers.getSize());
        Map<Long, UserProfileEntity> readProfiles = readUsers.getRecords().isEmpty() ? Map.of()
                : userProfileMapper.selectBatchIds(
                        readUsers.getRecords().stream().map(UserEntity::getId).toList()).stream()
                        .collect(Collectors.toMap(UserProfileEntity::getUserId, Function.identity()));
        readUsers.getRecords().forEach(readUser -> {
            readPage.add(toResponse(readUser, readProfiles.get(readUser.getId())));
        });
        return readPage;
    }

    @Transactional
    @Override
    public void updateUser(Long id, UserRequest request) {
        UserEntity entity = BeanUtil.copyProperties(request, UserEntity.class);
        entity.setId(id);
        updateById(entity);
    }

    @Transactional
    @Override
    public UserResponse updateRole(Long id, UserRole role) {
        UserEntity updateUser = new UserEntity();
        updateUser.setId(id);
        updateUser.setRole(role);
        if (!updateById(updateUser)) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return getUserById(id);
    }

    private UserResponse toResponse(UserEntity readUser, UserProfileEntity readProfile) {
        if (readUser == null) return null;
        UserResponse readResponse = BeanUtil.copyProperties(readUser, UserResponse.class);
        readResponse.setRealName(readName(readUser.getStudentId(),
                readProfile == null ? null : readProfile.getRealName(), readUser.getUsername()));
        return readResponse;
    }
}
