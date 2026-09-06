package com.fusioncareer.config;

import cn.dev33.satoken.stp.StpInterface;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UserRoleProvider implements StpInterface {

    private final UserService readUserService;

    @Override
    public List<String> getPermissionList(Object readLoginId, String readLoginType) {
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object readLoginId, String readLoginType) {
        UserEntity readUser = readUserService.getById(Long.valueOf(String.valueOf(readLoginId)));
        if (readUser == null || readUser.getStatus() == UserStatus.DISABLED || readUser.getRole() == null) {
            return List.of();
        }
        return List.of(readUser.getRole().name());
    }
}
