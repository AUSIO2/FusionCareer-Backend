package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Internal - 用户账号管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
@Tag(name = "内部用户账号管理接口", description = "Internal - 用户账号管理接口")
public class InternalUserController {

    private final UserService userService;

    @PostMapping
    @Operation(summary = "创建用户")
    public R<UserResponse> create(@RequestBody UserRequest request) {
        return R.success(userService.createUser(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情")
    public R<UserResponse> getById(@PathVariable Long id) {
        return R.success(userService.getUserById(id));
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询用户列表")
    public R<PageResult<UserResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username) {
        return R.success(userService.listUsers(page, size, username));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户信息")
    public R<Void> update(@PathVariable Long id, @RequestBody UserRequest request) {
        userService.updateUser(id, request);
        return R.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public R<Void> delete(@PathVariable Long id) {
        userService.removeById(id);
        return R.success();
    }
}
