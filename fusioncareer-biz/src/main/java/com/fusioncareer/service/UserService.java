package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserEntity;

public interface UserService extends IService<UserEntity> {

    UserResponse createUser(UserRequest request);

    UserResponse getUserById(Long id);

    PageResult<UserResponse> listUsers(int page, int size, String username);

    void updateUser(Long id, UserRequest request);
}
