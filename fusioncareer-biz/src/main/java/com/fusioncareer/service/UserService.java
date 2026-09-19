package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserRole;
import java.util.List;

public interface UserService extends IService<UserEntity> {

    UserResponse createUser(UserRequest request);

    UserResponse getUserById(Long id);

    PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role);

    PageResult<UserResponse> listUsers(int page, int size, String username, UserRole role, List<Long> userIds);

    void updateUser(Long id, UserRequest request);

    UserResponse updateRole(Long id, UserRole role);
}
