package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.mapper.UserMapper;
import com.fusioncareer.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements UserService {

    @Transactional
    @Override
    public UserResponse createUser(UserRequest request) {
        UserEntity entity = new UserEntity();
        BeanUtils.copyProperties(request, entity);
        save(entity);
        return toResponse(entity);
    }

    @Override
    public UserResponse getUserById(Long id) {
        return toResponse(getById(id));
    }

    @Override
    public PageResult<UserResponse> listUsers(int page, int size, String username) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(username), UserEntity::getUsername, username)
               .orderByDesc(UserEntity::getCreatedAt);

        Page<UserEntity> readUsers = page(createPage(page, size), wrapper);

        PageResult<UserResponse> readPage = new PageResult<>(readUsers.getTotal(),
                (int) readUsers.getCurrent(), (int) readUsers.getSize());
        readUsers.getRecords().forEach(e -> readPage.add(toResponse(e)));
        return readPage;
    }

    @Transactional
    @Override
    public void updateUser(Long id, UserRequest request) {
        UserEntity entity = new UserEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setId(id);
        updateById(entity);
    }

    private UserResponse toResponse(UserEntity entity) {
        if (entity == null) return null;
        UserResponse resp = new UserResponse();
        BeanUtils.copyProperties(entity, resp);
        return resp;
    }
}
