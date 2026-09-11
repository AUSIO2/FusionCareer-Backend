package com.fusioncareer.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SaCheckRole("ADMIN")
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
@Tag(name = "管理员用户接口", description = "管理员查看用户并调整角色")
public class AdminUserController {

    private final UserService userService;

    @GetMapping("/list")
    @Operation(summary = "分页查询用户列表")
    public R<PageResult<UserResponse>> readUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) UserRole role) {
        return R.success(userService.listUsers(page, size, username, role));
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "修改用户角色")
    public R<UserResponse> updateRole(
            @PathVariable("id") Long updateId,
            @RequestParam UserRole role) {
        if (role == UserRole.NORMAL && updateId.equals(StpUtil.getLoginIdAsLong())) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "不能撤销自己的管理员权限");
        }
        return R.success(userService.updateRole(updateId, role));
    }
}
