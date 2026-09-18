package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.UserMapper;
import com.fusioncareer.service.UserService;
import cn.hutool.core.bean.BeanUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements UserService {

    @Transactional
    @Override
    public UserResponse createUser(UserRequest request) {
        UserEntity entity = BeanUtil.copyProperties(request, UserEntity.class);
        save(entity);
        return toResponse(entity);
    }

    @Override
    public UserResponse getUserById(Long id) {
        return toResponse(getById(id));
    }

    @Override
    public PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role) {
        return listUsers(page, size, username, role, null);
    }

    @Override
    public PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role, List<Long> userIds) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(StringUtils.hasText(username), readQuery -> readQuery
                       .like(UserEntity::getUsername, username)
                       .or()
                       .like(UserEntity::getStudentId, username))
               .eq(role != null, UserEntity::getRole, role)
               .in(userIds != null && !userIds.isEmpty(), UserEntity::getId, userIds)
               .orderByDesc(UserEntity::getCreatedAt)
               .orderByDesc(UserEntity::getId);

        Page<UserEntity> readUsers = page(createPage(page, size), wrapper);

        PageResult<UserResponse> readPage = new PageResult<>(readUsers.getTotal(),
                (int) readUsers.getCurrent(), (int) readUsers.getSize());
        readUsers.getRecords().forEach(e -> readPage.add(toResponse(e)));
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

    private UserResponse toResponse(UserEntity entity) {
        if (entity == null) return null;
        UserResponse resp = BeanUtil.copyProperties(entity, UserResponse.class);
        return resp;
    }
}
