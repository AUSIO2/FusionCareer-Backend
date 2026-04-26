package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 鉴权测试接口
 * <p>
 * 提供基础的登录/注销/鉴权演示功能。
 * 后续对接 CAS/OAuth2 单点登录时，此控制器将作为模板进行改造。
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /**
     * 模拟登录（发放 Token）
     *
     * @param userId 用户ID（模拟）
     * @return 登录成功后的 Token 信息
     */
    @GetMapping("/login")
    public R<Map<String, Object>> login(@RequestParam(defaultValue = "1001") long userId) {
        // 第一步：标记当前会话登录的用户 id
        StpUtil.login(userId);

        // 第二步：获取当前会话的 Token 信息
        Map<String, Object> tokenInfo = new HashMap<>();
        tokenInfo.put("tokenName", StpUtil.getTokenName());
        tokenInfo.put("tokenValue", StpUtil.getTokenValue());
        tokenInfo.put("loginId", StpUtil.getLoginId());
        tokenInfo.put("tokenTimeout", StpUtil.getTokenTimeout());

        return R.success(tokenInfo, "登录成功");
    }

    /**
     * 注销登录（销毁 Token）
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        StpUtil.logout();
        return R.success();
    }

    /**
     * 查询当前登录状态
     */
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> info = new HashMap<>();
        info.put("isLogin", StpUtil.isLogin());

        if (StpUtil.isLogin()) {
            info.put("loginId", StpUtil.getLoginId());
            info.put("tokenValue", StpUtil.getTokenValue());
            info.put("tokenTimeout", StpUtil.getTokenTimeout());
        }

        return R.success(info);
    }

    /**
     * 需要登录才能访问的接口示例
     */
    @SaCheckLogin
    @GetMapping("/test-login")
    public R<String> testLogin() {
        return R.success("您已通过登录校验，当前用户ID: " + StpUtil.getLoginId());
    }

    /**
     * 需要 admin 角色才能访问的接口示例
     * <p>
     * 注意：当前未配置 StpInterface 实现类，因此所有角色检查都会被拒绝。
     * 后续需实现 StpInterface 接口来对接数据库中的角色/权限数据。
     */
    @SaCheckRole("admin")
    @GetMapping("/test-role")
    public R<String> testRole() {
        return R.success("您拥有 admin 角色，当前用户ID: " + StpUtil.getLoginId());
    }
}
