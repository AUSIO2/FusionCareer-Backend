package com.fusioncareer.controller.internal;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Internal - 用户账号管理接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    @PostMapping
    public R<UserResponse> create(@RequestBody UserRequest request) {
        return R.success(userService.createUser(request));
    }

    @GetMapping("/{id}")
    public R<UserResponse> getById(@PathVariable Long id) {
        return R.success(userService.getUserById(id));
    }

    @GetMapping("/list")
    public R<PageResult<UserResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username) {
        return R.success(userService.listUsers(page, size, username));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody UserRequest request) {
        userService.updateUser(id, request);
        return R.success();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.removeById(id);
        return R.success();
    }
}
